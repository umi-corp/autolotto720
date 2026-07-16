package com.umicorp.autolotto720.dhlottery

/**
 * 동행복권 세션용 커스텀 쿠키 저장소 — Flutter AuthService._domainCookies / _parseCookies /
 * _buildCookieHeader 로직의 1:1 포트.
 *
 * 표준 쿠키 처리(OkHttp 기본 CookieJar 포함)는 JSESSIONID를 www.↔ol. 서브도메인 사이로
 * 전달하지 않으므로, Set-Cookie를 직접 파싱하고 도메인 매칭 규칙도 직접 구현한다.
 *
 * 저장: domain(키) → (name → value). Set-Cookie에 `Domain=`이 있으면 그 값(점 포함 원문),
 *       없으면 요청 host를 domain 키로 사용.
 * 전송: '.'으로 시작하는 domain은 host가 그 suffix이거나 ".suffix"로 끝나면 포함,
 *       그리고 host와 정확히 일치하는 domain의 쿠키 포함.
 */
class DomainCookieStore {
    private val domainCookies = LinkedHashMap<String, LinkedHashMap<String, String>>()

    @Synchronized
    fun clear() {
        domainCookies.clear()
    }

    /** 요청 host에 보낼 Cookie 헤더 ("k=v; k=v"). 없으면 빈 문자열. */
    @Synchronized
    fun cookieHeader(host: String): String {
        val merged = LinkedHashMap<String, String>()
        for ((domain, cookies) in domainCookies) {
            if (domain.startsWith(".")) {
                val suffix = domain.substring(1)
                if (host == suffix || host.endsWith(".$suffix")) merged.putAll(cookies)
            }
        }
        domainCookies[host]?.let { merged.putAll(it) }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 응답의 Set-Cookie 헤더 목록을 파싱해 저장 (Dart _parseCookies 그대로). */
    @Synchronized
    fun store(setCookieHeaders: List<String>, requestHost: String) {
        for (cookie in setCookieHeaders) {
            val mainPart = cookie.substringBefore(';')
            val eqIdx = mainPart.indexOf('=')
            if (eqIdx < 1) continue
            val name = mainPart.substring(0, eqIdx).trim()
            val value = mainPart.substring(eqIdx + 1).trim()

            var domain = requestHost
            val lower = cookie.lowercase()
            val domainIdx = lower.indexOf("domain=")
            if (domainIdx != -1) {
                val afterDomain = cookie.substring(domainIdx + 7)
                val endIdx = afterDomain.indexOf(';')
                domain = (if (endIdx != -1) afterDomain.substring(0, endIdx) else afterDomain).trim()
            }
            domainCookies.getOrPut(domain) { LinkedHashMap() }[name] = value
        }
    }

    @Synchronized
    fun hasCookie(name: String): Boolean = domainCookies.values.any { it.containsKey(name) }

    /** [host]에 전송될 쿠키 중 [name]의 값 (도메인 매칭 규칙은 [cookieHeader]와 동일). 없으면 null.
     *  720 구매 암호화 passphrase(el JSESSIONID) 조회용 — el 세션과 www 세션의 JSESSIONID가 다를 수 있어
     *  전역이 아닌 host 기준으로 정확히 뽑아야 한다. */
    @Synchronized
    fun cookieValue(host: String, name: String): String? {
        var value: String? = null
        for ((domain, cookies) in domainCookies) {
            if (domain.startsWith(".")) {
                val suffix = domain.substring(1)
                if (host == suffix || host.endsWith(".$suffix")) cookies[name]?.let { value = it }
            }
        }
        domainCookies[host]?.get(name)?.let { value = it }  // 정확 일치가 도메인 매칭을 덮어씀(최종 우선)
        return value
    }

    @Synchronized
    fun keysForDomain(domain: String): List<String> =
        domainCookies[domain]?.keys?.toList() ?: emptyList()
}
