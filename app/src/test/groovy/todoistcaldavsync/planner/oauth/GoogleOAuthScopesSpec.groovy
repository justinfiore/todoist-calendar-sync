package todoistcaldavsync.planner.oauth

import spock.lang.Specification

class GoogleOAuthScopesSpec extends Specification {
    def "canonicalize normalizes only Google identity aliases"() {
        expect:
        GoogleOAuthScopes.canonicalize([
            'openid',
            GoogleOAuthScopes.USERINFO_EMAIL,
            GoogleOAuthScopes.USERINFO_PROFILE,
            'https://www.googleapis.com/auth/drive'
        ]) == [
            'openid',
            'email',
            'profile',
            'https://www.googleapis.com/auth/drive'
        ] as Set
    }
}
