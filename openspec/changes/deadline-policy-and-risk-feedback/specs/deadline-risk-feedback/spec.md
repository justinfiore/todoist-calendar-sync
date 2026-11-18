# Spec Delta

## Purpose

Help users recognize and mitigate threatened native Todoist Deadlines through concise numbered Slack proposal tables and safe, LLM-interpreted task edits, equally for hard/soft and recurring/non-recurring tasks.

## ADDED Requirements

### Requirement: Plan proposals identify hard and soft Deadline risk
The system SHALL warn about tasks within the applicable hard/soft warning window, including overdue tasks, when the proposed plan leaves them unscheduled or finishing after native Deadline. All tasks with Deadline SHALL qualify regardless of recurrence or label selection. An unscheduled task beyond the proposal horizon SHALL be marked as unverified on-time coverage, not proven impossibility.

#### Scenario: Late soft and unscheduled hard tasks
- **WHEN** an in-window soft task is placed late and an in-window hard task is unscheduled
- **THEN** the Slack proposal SHALL identify both as at risk and distinguish their effective modes

#### Scenario: Short horizon cannot establish coverage
- **WHEN** a task's Deadline is within its warning window but after the proposal horizon and it is unscheduled
- **THEN** the proposal SHALL include the warning and explain that the horizon ends before Deadline, so on-time coverage is unverified

#### Scenario: No qualifying risk
- **WHEN** every task is either outside its warning window or fully planned on time
- **THEN** the proposal SHALL NOT assert that those Deadlines are at risk

### Requirement: Slack warnings expose numbered task context in a table
Slack proposals SHALL show at-risk tasks in a native Slack table. Each row SHALL include a proposal-specific number, task name linked to Todoist, project, native Deadline date, effective hard/soft mode, and user-facing P1–P4 priority. The proposal SHALL ask whether the user wants to change Deadline or raise priority and show a concise multi-task reply example.

#### Scenario: Multiple warnings need context
- **WHEN** a proposal has several at-risk tasks, including similarly named tasks in different projects
- **THEN** each row SHALL expose the required fields and a distinct number
- **AND** links SHALL resolve to the corresponding task identities without relying on title slugs

### Requirement: Numbered replies accept concise natural language and fuzzy dates
An authorized user SHALL be able to request multiple mitigations in one proposal-thread reply with each task number preceding or appearing elsewhere in its instruction line. The interpreter SHALL support absolute and fuzzy dates, relative Deadline delays, and explicit P1–P4 priority targets. Relative delays SHALL use the task's current Deadline; date interpretation SHALL use the configured timezone and reply reference time.

#### Scenario: Mixed short instructions
- **WHEN** a reply says “1 elevate to P2”, “change deadline to Dec 5 for 2”, and “3 push back two weeks” on separate instruction lines
- **THEN** the interpreter SHALL resolve the corresponding proposal-bound tasks and return the requested native priority/date actions
- **AND** the relative delay SHALL be measured from task 3's current Deadline

#### Scenario: Fuzzy date has a clear interpretation
- **WHEN** an authorized reply uses a fuzzy date such as “next Friday” with sufficient date/timezone context
- **THEN** the interpreter SHALL resolve it to a specific local Deadline date without requiring ISO input

### Requirement: Unresolvable replies produce clarification before writes
The LLM interpretation SHALL resolve all requested instructions into bounded actions or return a clarification result. If any target, date, priority, or instruction is unresolved, the application SHALL post a clarification question in the same Slack thread and SHALL perform no task mutations for that reply. Invalid or unavailable model output SHALL NOT grant write authority.

#### Scenario: One of several instructions is unclear
- **WHEN** two numbered instructions resolve but a third has an ambiguous target or date
- **THEN** the application SHALL ask for clarification in the proposal thread and SHALL apply none of the three

### Requirement: Validated authorized replies permit only the requested task edits
An unambiguous reply from an authorized user SHALL authorize only its validated native Deadline/date or priority changes without a second confirmation. Application code, not the LLM, SHALL enforce task identity, allowed fields, valid dates, user-facing priority mapping, and current-state preconditions before writes. Warning generation alone SHALL authorize nothing.

#### Scenario: User explicitly changes native priority
- **WHEN** a fully resolved authorized reply requests that row 1 become P2
- **THEN** the code SHALL change that task's native Todoist priority using the correct P1–P4/API mapping and SHALL NOT change its Deadline, Due, or unrelated properties

#### Scenario: Recurring task's Deadline is delayed
- **WHEN** a validated authorized reply delays the current Deadline of a recurring task
- **THEN** the code SHALL preserve its native Due/recurrence tuple, completion state, unrelated fields, and lifecycle consistency
- **AND** SHALL NOT complete or reopen the task to effect the edit

### Requirement: Proposal identity and replay safety bound feedback authority
Task numbers SHALL remain bound to their exact proposal, thread, and task identities across restart and later proposals. The system SHALL refuse unknown or stale targets rather than remapping numbers, deduplicate reply execution, verify provider outcomes, and preserve reconciliation evidence. A stale-state refusal SHALL request refreshed review instead of overwriting a concurrent human edit.

#### Scenario: Same number appears in a newer proposal
- **WHEN** row 1 in a newer proposal refers to another task
- **THEN** a reply in the older thread SHALL NOT be applied to the newer row 1

#### Scenario: Reply is redelivered after restart
- **WHEN** an already applied Slack event is delivered again
- **THEN** the system SHALL retain the original result without repeating a relative date delay or other write

### Requirement: Results distinguish validation from provider execution
All instructions SHALL pass interpretation and preflight validation before the first mutation. Provider execution SHALL NOT be presented as an atomic cross-task transaction. The app SHALL report verified, rejected, and ambiguous outcomes accurately, reconcile uncertain writes without blind replay, and regenerate a fresh proposal after verified changes without granting Calendar apply approval.

#### Scenario: Provider fails after another task changed
- **WHEN** a fully validated reply has one verified mutation followed by a rejected or ambiguous mutation
- **THEN** the thread reply SHALL identify the actual outcomes and SHALL NOT claim that nothing changed or that the whole batch succeeded
- **AND** recovery SHALL NOT blindly replay the completed mutation or automatically roll it back

#### Scenario: Warning or ordinary preview runs without a mitigation reply
- **WHEN** a plan is rendered, delivered, or previewed without a validated authorized mitigation reply
- **THEN** it SHALL send no Deadline or priority mutation
