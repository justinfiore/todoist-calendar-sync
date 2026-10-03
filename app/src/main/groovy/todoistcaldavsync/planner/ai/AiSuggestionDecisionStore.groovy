package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import groovy.transform.PackageScope
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.state.PlanStoreException

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface AtomicFileMover { void move(Path source, Path target) }

/** Cross-process locked, fail-closed decision ledger. */
final class AiSuggestionDecisionStore {
    static final int SCHEMA_VERSION = 1
    private static final ConcurrentHashMap<String,Object> PROCESS_LOCKS = new ConcurrentHashMap<>()
    private static final AtomicFileMover DEFAULT_MOVER = { Path source, Path target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } as AtomicFileMover

    private final Path directory
    private final byte[] signingKey
    private final Runnable beforeMoveHook
    private final AtomicFileMover mover

    AiSuggestionDecisionStore(Path directory) {
        throw new IllegalArgumentException('decision signing key is required')
    }
    AiSuggestionDecisionStore(Path directory, String signingKey, Runnable beforeMoveHook = null, AtomicFileMover mover = DEFAULT_MOVER) {
        this(directory, signingKey?.getBytes(StandardCharsets.UTF_8), beforeMoveHook, mover)
    }
    AiSuggestionDecisionStore(Path directory, byte[] signingKey, Runnable beforeMoveHook = null, AtomicFileMover mover = DEFAULT_MOVER) {
        if (directory == null) throw new IllegalArgumentException('directory is required')
        validateSigningKey(signingKey)
        this.directory=directory.toAbsolutePath().normalize();this.signingKey=signingKey.clone()
        this.beforeMoveHook=beforeMoveHook;this.mover=mover ?: DEFAULT_MOVER
    }
    Path recordsPath(){contained(directory.resolve('ai-suggestion-decisions.json'))}
    private Path identityPath(){contained(directory.resolve('.ai-suggestion-store-id'))}
    private Path lockPath(){contained(directory.resolve('.ai-suggestion-decisions.lock'))}

    @PackageScope DecisionAppendOutcome appendClassified(AiSuggestionDecisionRecord candidate) {
        if(candidate==null)throw new IllegalArgumentException('candidate is required')
        withLock {
            String storeId=loadOrCreateStoreIdUnlocked()
            if(candidate.storeId!=storeId)throw new IllegalArgumentException('decision store provenance mismatch')
            List<AiSuggestionDecisionRecord> all=loadUnlocked(storeId)
            AiSuggestionDecisionRecord correlationPrior=all.find{it.correlationId==candidate.correlationId}
            AiSuggestionDecisionRecord identityPrior=all.find{it.isOriginalTerminal() && it.sameSuggestionIdentity(candidate)}
            AiSuggestionDecisionRecord prior=correlationPrior ?: identityPrior
            if(prior!=null){
                boolean same=prior.sameSuggestionIdentity(candidate) && prior.action==candidate.action &&
                    (prior.isOriginalTerminal() || prior.status=='IDEMPOTENT_REPLAY')
                String status=same?'IDEMPOTENT_REPLAY':'REJECTED_REPLAY_CONFLICT'
                def replay=sign(candidate.withStatusAndPrior(status,prior.decisionId,allocateId(all,candidate,same?'replay':'conflict')))
                all << replay;saveUnlocked(all)
                return new DecisionAppendOutcome(same?'IDEMPOTENT_REPLAY':'CONFLICT',replay,prior,false)
            }
            def persisted=sign(candidate.withStatusAndPrior(candidate.status,null,
                allocateId(all,candidate,candidate.confirmed?'confirmed':'rejected')))
            all << persisted;saveUnlocked(all)
            new DecisionAppendOutcome(persisted.confirmed?'NEW_CONFIRMED':'NEW_REJECTED',persisted,null,persisted.confirmed)
        }
    }

    List<AiSuggestionDecisionRecord> list(){withLock{
        if(!Files.exists(identityPath()))return Collections.emptyList()
        Collections.unmodifiableList(loadUnlocked(loadStoreIdUnlocked()))
    }}

    /** Reloads and verifies the exact authorizing record from this store. */
    TemporaryPlanningOverride verifiedOverride(String decisionId,AiSuggestionBundle bundle,Plan plan,Instant now){
        verifiedOverride(decisionId,bundle,plan,Collections.emptyList(),now)
    }
    TemporaryPlanningOverride verifiedOverride(String decisionId,AiSuggestionBundle bundle,Plan plan,
                                                 Collection<CalendarEvent> events,Instant now){
        if(now==null)throw new IllegalArgumentException('authoritative now is required')
        if(!OpaqueIdentifier.valid(decisionId)||bundle==null||plan==null)throw new IllegalArgumentException('decisionId, bundle, and plan are required')
        if(!LlmSchemaValidator.isAuthentic(bundle))throw new IllegalArgumentException('validator-issued bundle is required')
        withLock {
            String storeId=loadStoreIdUnlocked()
            AiSuggestionDecisionRecord record=loadUnlocked(storeId).find{it.decisionId==decisionId}
            if(record==null || record.storeId!=storeId || record.status!='CONFIRMED_TEMPORARY_OVERRIDE' ||
                record.action!='CONFIRM' || record.schemaType!='temporary_planning_overrides' || record.schemaVersion!=1 ||
                record.bundleContentHash!=bundle.contentHash || record.planId!=bundle.planId ||
                record.planVersion!=bundle.planVersion || record.planHash!=bundle.planHash ||
                record.planningInputHash!=bundle.planningInputHash ||
                bundle.planId!=plan.id || bundle.planVersion!=plan.version || bundle.planHash!=PlanHash.compute(plan) ||
                bundle.planningInputHash!=PlanningInputHash.compute(plan,events)) {
                throw new IllegalArgumentException('exact persisted confirmed override decision is required')
            }
            AiSuggestion suggestion=bundle.find(record.suggestionId)
            if(!(suggestion instanceof TemporaryPlanningOverride))throw new IllegalArgumentException('decision suggestion type mismatch')
            TemporaryPlanningOverride override=suggestion as TemporaryPlanningOverride
            if(override.planId!=record.planId || override.planVersion!=record.planVersion || override.planHash!=record.planHash ||
                override.planningInputHash!=record.planningInputHash || !override.expiresAt.isAfter(now))
                throw new IllegalArgumentException('override is expired or stale')
            override
        }
    }

    @PackageScope String provenanceId(){withLock{loadOrCreateStoreIdUnlocked()}}

    private List<AiSuggestionDecisionRecord> loadUnlocked(String expectedStoreId){
        Path p=recordsPath();if(!Files.exists(p))return []
        try{
            def root=StrictJson.parseObject(Files.readString(p,StandardCharsets.UTF_8))
            if(!(root instanceof Map)||root.keySet() as Set!=['schemaVersion','storeId','records'] as Set||
                root.schemaVersion!=SCHEMA_VERSION||root.storeId!=expectedStoreId||!(root.records instanceof List))throw new IllegalArgumentException('invalid root')
            (root.records as List).collect{
                AiSuggestionDecisionRecord record=AiSuggestionDecisionRecord.fromMap(it as Map,expectedStoreId)
                if(!authenticated(record))throw new IllegalArgumentException('invalid decision signature')
                record
            }
        }catch(PlanStoreException e){throw e}
        catch(Exception e){throw new PlanStoreException('Corrupt AI suggestion decision store',p.toString(),'load',e)}
    }
    private void saveUnlocked(List<AiSuggestionDecisionRecord> records){
        String storeId=loadStoreIdUnlocked()
        if(!records.every{it.storeId==storeId && authenticated(it)})throw new IllegalArgumentException('unsigned decision record')
        String json=JsonOutput.prettyPrint(JsonOutput.toJson([schemaVersion:SCHEMA_VERSION,storeId:storeId,records:records.collect{it.toMap()}]))
        atomicWrite(recordsPath(),json,'decisions')
    }
    private String loadOrCreateStoreIdUnlocked(){
        if(Files.exists(identityPath()))return loadStoreIdUnlocked()
        String id=UUID.randomUUID().toString()
        atomicWrite(identityPath(),id+'\n','identity')
        id
    }
    private String loadStoreIdUnlocked(){
        try{
            String id=Files.readString(identityPath(),StandardCharsets.UTF_8).trim()
            if(!(id==~/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/))throw new IllegalArgumentException('invalid id')
            id
        }catch(Exception e){throw new PlanStoreException('Corrupt AI decision store identity',identityPath().toString(),'load',e)}
    }
    private void atomicWrite(Path target,String text,String kind){
        Path temp=null
        try{
            temp=Files.createTempFile(directory,".ai-${kind}.",'.tmp')
            Files.writeString(temp,text,StandardCharsets.UTF_8,StandardOpenOption.TRUNCATE_EXISTING)
            FileChannel.open(temp,StandardOpenOption.WRITE).withCloseable{it.force(true)}
            if(beforeMoveHook!=null)beforeMoveHook.run()
            mover.move(temp,target)
            temp=null
            try{FileChannel.open(directory,StandardOpenOption.READ).withCloseable{it.force(true)}}catch(Exception ignored){}
        }catch(Exception e){throw new PlanStoreException('Failed to atomically save AI decision store',target.toString(),'save',e)}
        finally{if(temp!=null)try{Files.deleteIfExists(temp)}catch(Exception ignored){}}
    }
    private String allocateId(List all,AiSuggestionDecisionRecord c,String kind){
        String stem="ai-${kind}-"+AiValues.sha256([kind,c.storeId,c.correlationId,c.suggestionId,c.bundleContentHash,c.actorId,c.planHash,c.planningInputHash].join('|')).substring(0,20)
        String id=stem;int n=1;Set ids=all.collect{it.decisionId} as Set
        while(ids.contains(id))id="${stem}-${n++}"
        id
    }
    private AiSuggestionDecisionRecord sign(AiSuggestionDecisionRecord record){
        record.withSignature(hex(hmac(record)))
    }
    private boolean authenticated(AiSuggestionDecisionRecord record){
        try{
            if(record?.signature==null||!(record.signature==~/^[0-9a-f]{64}$/))return false
            MessageDigest.isEqual(hmac(record),decodeHex(record.signature))
        }catch(Exception ignored){false}
    }
    private byte[] hmac(AiSuggestionDecisionRecord record){
        Mac mac=Mac.getInstance('HmacSHA256')
        mac.init(new SecretKeySpec(signingKey,'HmacSHA256'))
        mac.doFinal(record.canonicalSigningBytes())
    }
    private static String hex(byte[] bytes){bytes.collect{String.format('%02x',it&0xff)}.join()}
    private static byte[] decodeHex(String value){
        byte[] out=new byte[value.length()/2]
        for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16)
        out
    }
    private static void validateSigningKey(byte[] key){
        if(key==null)throw new IllegalArgumentException('decision signing key is required')
        if(key.length<32 || (key.collect{it} as Set).size()<16) {
            throw new IllegalArgumentException('decision signing key must contain at least 256 bits of high-entropy material')
        }
    }
    private <T>T withLock(Closure<T> action){
        try{Files.createDirectories(directory)}catch(Exception e){throw new PlanStoreException('Failed to create AI decision directory',directory.toString(),'save',e)}
        String key=lockPath().toString();Object monitor=PROCESS_LOCKS.computeIfAbsent(key,{new Object()})
        synchronized(monitor){
            FileChannel channel=null;FileLock lock=null
            try{channel=FileChannel.open(lockPath(),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);lock=channel.lock();action.call()}
            catch(PlanStoreException|IllegalArgumentException e){throw e}
            catch(Exception e){throw new PlanStoreException('AI decision lock failure',lockPath().toString(),'lock',e)}
            finally{if(lock!=null)try{lock.release()}catch(Exception ignored){};if(channel!=null)try{channel.close()}catch(Exception ignored){}}
        }
    }
    private Path contained(Path path){Path normalized=path.toAbsolutePath().normalize();if(!normalized.startsWith(directory))throw new IllegalArgumentException('decision path escapes store');normalized}
}

final class AiSuggestionDecisionRecord {
    static final Set<String> FIELDS=['storeId','decisionId','suggestionId','schemaType','schemaVersion','bundleContentHash','planId','planVersion','planHash','planningInputHash','actorId','correlationId','decidedAt','action','status','priorDecisionId','signature'] as Set
    static final Set<String> STATUSES=['CONFIRMED_TEMPORARY_OVERRIDE','CONFIRMED_POLICY_SUGGESTION','CONFIRMED_FEEDBACK_INTERPRETATION','REJECTED_BY_USER','IDEMPOTENT_REPLAY','REJECTED_REPLAY_CONFLICT'] as Set
    final String storeId;final String decisionId;final String suggestionId;final String schemaType;final int schemaVersion
    final String bundleContentHash;final String planId;final int planVersion;final String planHash;final String planningInputHash;final String actorId
    final String correlationId;final Instant decidedAt;final String action;final String status;final String priorDecisionId;final String signature
    @PackageScope AiSuggestionDecisionRecord(Map v){
        storeId=req(v.storeId);decisionId=v.decisionId?.toString();suggestionId=req(v.suggestionId);schemaType=req(v.schemaType);schemaVersion=(v.schemaVersion?:0)as int
        bundleContentHash=req(v.bundleContentHash);planId=req(v.planId);planVersion=(v.planVersion?:0)as int;planHash=req(v.planHash);planningInputHash=req(v.planningInputHash);actorId=opaque(v.actorId);correlationId=opaque(v.correlationId)
        decidedAt=v.decidedAt instanceof Instant?v.decidedAt as Instant:Instant.parse(req(v.decidedAt));action=req(v.action);status=req(v.status);priorDecisionId=optionalOpaque(v.priorDecisionId);signature=v.signature?.toString()
        if(schemaVersion!=1||planVersion<1||!(action in ['CONFIRM','REJECT'] as Set)||!(schemaType in LlmSchemaValidator.TYPES)||!(status in STATUSES)||
            !(bundleContentHash==~/^[0-9a-f]{64}$/)||!(planHash==~/^[0-9a-f]{64}$/)||!(planningInputHash==~/^[0-9a-f]{64}$/)||(status.startsWith('CONFIRMED_')&&action!='CONFIRM')||
            (status=='REJECTED_BY_USER'&&action!='REJECT'))throw new IllegalArgumentException('invalid AI decision')
    }
    @PackageScope static AiSuggestionDecisionRecord fromMap(Map v,String expectedStoreId){
        if(v==null||v.keySet() as Set!=FIELDS)throw new IllegalArgumentException('AI decision fields mismatch')
        def r=new AiSuggestionDecisionRecord(v);if(!OpaqueIdentifier.valid(r.decisionId)||r.storeId!=expectedStoreId||!(r.signature==~/^[0-9a-f]{64}$/))throw new IllegalArgumentException('invalid persisted AI decision');r
    }
    boolean isConfirmed(){status.startsWith('CONFIRMED_')}
    boolean isOriginalTerminal(){isConfirmed()||status=='REJECTED_BY_USER'}
    boolean sameSuggestionIdentity(AiSuggestionDecisionRecord o){o!=null&&suggestionId==o.suggestionId&&schemaType==o.schemaType&&schemaVersion==o.schemaVersion&&bundleContentHash==o.bundleContentHash&&planId==o.planId&&planVersion==o.planVersion&&planHash==o.planHash&&planningInputHash==o.planningInputHash}
    @PackageScope AiSuggestionDecisionRecord withStatusAndPrior(String s,String prior,String id){new AiSuggestionDecisionRecord(toMap()+[decisionId:id,status:s,priorDecisionId:prior])}
    @PackageScope AiSuggestionDecisionRecord withSignature(String value){new AiSuggestionDecisionRecord(toMap()+[signature:value])}
    Map toMap(){[storeId:storeId,decisionId:decisionId,suggestionId:suggestionId,schemaType:schemaType,schemaVersion:schemaVersion,bundleContentHash:bundleContentHash,planId:planId,planVersion:planVersion,planHash:planHash,planningInputHash:planningInputHash,actorId:actorId,correlationId:correlationId,decidedAt:decidedAt.toString(),action:action,status:status,priorDecisionId:priorDecisionId,signature:signature]}
    byte[] canonicalSigningBytes(){JsonOutput.toJson(['ai-suggestion-decision-hmac-v1',storeId,decisionId,correlationId,actorId,action,status,suggestionId,schemaType,schemaVersion,bundleContentHash,planId,planVersion,planHash,planningInputHash,decidedAt.toString(),priorDecisionId]).getBytes(StandardCharsets.UTF_8)}
    private static String req(def v){if(!(v instanceof CharSequence))throw new IllegalArgumentException('required decision field missing');String s=v.toString();if(s.isEmpty())throw new IllegalArgumentException('required decision field missing');s}
    private static String opaque(def v){String s=v?.toString();if(!OpaqueIdentifier.valid(s))throw new IllegalArgumentException('invalid opaque identifier');s}
    private static String optionalOpaque(def v){if(v==null)return null;opaque(v)}
}
final class DecisionAppendOutcome {
    final String kind;final AiSuggestionDecisionRecord persisted;final AiSuggestionDecisionRecord existing;final boolean newlyConfirmed
    @PackageScope DecisionAppendOutcome(String k,AiSuggestionDecisionRecord p,AiSuggestionDecisionRecord e,boolean n){kind=k;persisted=p;existing=e;newlyConfirmed=n}
}

/** Explicit, authorized decision boundary. No apply/write dependency. */
final class AiSuggestionConfirmationService {
    static final Predicate<String> DENY_ALL={String ignored->false}as Predicate
    private final AiSuggestionDecisionStore store;private final Predicate<String> authorization
    AiSuggestionConfirmationService(AiSuggestionDecisionStore store,Predicate<String> authorization=DENY_ALL){if(store==null)throw new IllegalArgumentException('store required');this.store=store;this.authorization=authorization?:DENY_ALL}
    static Predicate<String> allowlist(Collection<String> actors){Set a=(actors?:[]).collect{String value=it?.toString();if(!OpaqueIdentifier.valid(value))throw new IllegalArgumentException('invalid allowlisted actor');value}as Set;{String actor->actor!=null&&a.contains(actor)}as Predicate}

    ConfirmationResult decide(AiSuggestionBundle bundle,String suggestionId,Plan currentPlan,String actor,String correlation,String action,Instant now){
        decide(bundle,suggestionId,currentPlan,Collections.emptyList(),actor,correlation,action,now)
    }
    ConfirmationResult decide(AiSuggestionBundle bundle,String suggestionId,Plan currentPlan,Collection<CalendarEvent> currentEvents,
                              String actor,String correlation,String action,Instant now){
        if(now==null)return ConfirmationResult.reject('authoritative now is required')
        if(bundle==null||currentPlan==null||!LlmSchemaValidator.isAuthentic(bundle))return ConfirmationResult.reject('validator-issued bundle and current plan are required')
        String who=actor
        if(!OpaqueIdentifier.valid(who))return ConfirmationResult.reject('actor is not a valid opaque identifier')
        if(!OpaqueIdentifier.valid(correlation))return ConfirmationResult.reject('confirmation correlation is not a valid opaque identifier')
        boolean allowed=false;try{allowed=authorization.test(who)}catch(Exception ignored){}
        if(!allowed)return ConfirmationResult.reject('actor is not authorized')
        if(!(action in ['CONFIRM','REJECT'] as Set))return ConfirmationResult.reject('action must be CONFIRM or REJECT')
        String currentHash=PlanHash.compute(currentPlan)
        String currentInputHash
        try{currentInputHash=PlanningInputHash.compute(currentPlan,currentEvents)}catch(Exception ignored){return ConfirmationResult.reject('unable to bind current planning input')}
        if(bundle.planId!=currentPlan.id||bundle.planVersion!=currentPlan.version||bundle.planHash!=currentHash||
            bundle.planningInputHash!=currentInputHash)return ConfirmationResult.reject('stale or wrong planning input identity')
        AiSuggestion suggestion=bundle.find(suggestionId);if(suggestion==null)return ConfirmationResult.reject('suggestion is not in exact bundle')
        if(suggestion instanceof TemporaryPlanningOverride){def o=suggestion as TemporaryPlanningOverride;if(!o.expiresAt.isAfter(now)||o.planId!=bundle.planId||o.planVersion!=bundle.planVersion||o.planHash!=bundle.planHash||o.planningInputHash!=bundle.planningInputHash)return ConfirmationResult.reject('override is expired or stale')}
        String status=action=='REJECT'?'REJECTED_BY_USER':suggestion instanceof TemporaryPlanningOverride?'CONFIRMED_TEMPORARY_OVERRIDE':suggestion instanceof ProposedStructuredFeedback?'CONFIRMED_FEEDBACK_INTERPRETATION':'CONFIRMED_POLICY_SUGGESTION'
        def record=new AiSuggestionDecisionRecord([storeId:store.provenanceId(),decisionId:null,suggestionId:suggestion.suggestionId,schemaType:bundle.suggestionType,schemaVersion:bundle.schemaVersion,bundleContentHash:bundle.contentHash,planId:bundle.planId,planVersion:bundle.planVersion,planHash:bundle.planHash,planningInputHash:bundle.planningInputHash,actorId:who,correlationId:correlation,decidedAt:now,action:action,status:status,priorDecisionId:null])
        DecisionAppendOutcome outcome=store.appendClassified(record)
        if(!outcome.newlyConfirmed)return new ConfirmationResult(false,outcome.kind,null,outcome.persisted)
        String command=suggestion instanceof ProposedStructuredFeedback?(suggestion as ProposedStructuredFeedback).proposedCommand:null
        new ConfirmationResult(true,'NEW_CONFIRMED',command,outcome.persisted)
    }
    ConfirmationResult confirmInterpretation(AiSuggestionBundle bundle,String suggestionId,Plan plan,String actor,String correlation,Instant now){
        if(!(bundle?.find(suggestionId) instanceof ProposedStructuredFeedback))return ConfirmationResult.reject('suggestion is not feedback interpretation')
        decide(bundle,suggestionId,plan,actor,correlation,'CONFIRM',now)
    }
    ConfirmationResult confirmInterpretation(AiSuggestionBundle bundle,String suggestionId,Plan plan,
                                             Collection<CalendarEvent> events,String actor,String correlation,Instant now){
        if(!(bundle?.find(suggestionId) instanceof ProposedStructuredFeedback))return ConfirmationResult.reject('suggestion is not feedback interpretation')
        decide(bundle,suggestionId,plan,events,actor,correlation,'CONFIRM',now)
    }
}

final class OpaqueIdentifier {
    private static final java.util.regex.Pattern SYNTAX=java.util.regex.Pattern.compile(/^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$/)
    private static final java.util.regex.Pattern SENSITIVE=java.util.regex.Pattern.compile(
        /(?i)(?:^|[._:@-])(?:authorization|bearer|client[._-]?secret|access[._-]?token|refresh[._-]?token|private[._-]?key|api[._-]?key|auth[._-]?token|bearer[._-]?token|webhook(?:[._-]?url)?|signing[._-]?secret|password|credential|secret|token|key)(?:[._:@-]|$)|^(?:sk|xox[baprs]|gh[pousr])[-_]/)
    private OpaqueIdentifier(){}
    static boolean valid(String value){
        if(value==null||!SYNTAX.matcher(value).matches()||SENSITIVE.matcher(value).find())return false
        def redacted=AiRedactor.redactText(value,128)
        redacted.redactionCount==0&&redacted.text==value
    }
}
final class ConfirmationResult {
    final boolean confirmed;final String outcome;final String structuredCommand;final AiSuggestionDecisionRecord record
    @PackageScope ConfirmationResult(boolean c,String o,String command,AiSuggestionDecisionRecord r){confirmed=c;outcome=o;structuredCommand=command;record=r}
    static ConfirmationResult reject(String reason){new ConfirmationResult(false,reason,null,null)}
}
