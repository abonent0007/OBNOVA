package com.example.nova

/**
 * Снимок списков на момент запроса.
 *
 * Пока списки приходят из файлов приложения; сетевую синхронизацию добавим
 * отдельным слоем, не меняя интерфейс. Снимок неизменяемый: политика решений
 * не должна видеть список, который кто-то правит в этот же момент.
 */
class RoutingListsSnapshot(
    private val exclude: DomainList = DomainList.EMPTY,
    private val second: DomainList = DomainList.EMPTY,
    private val main: DomainList = DomainList.EMPTY,
    private val ai: DomainList = DomainList.EMPTY,
    private val youtube: DomainList = DomainList.EMPTY,
    private val telegram: DomainList = DomainList.EMPTY,
    private val whatsapp: DomainList = DomainList.EMPTY,
    private val discord: DomainList = DomainList.EMPTY,
    private val cloudflareIps: Set<String> = emptySet(),
    private val cloudflareRanges: List<Ipv4Range> = emptyList(),
    private val localTlds: Set<String> = RoutingPolicy.LOCAL_TLDS,
) : RoutingLists {

    override fun inExclude(host: String) = exclude.contains(host)
    override fun inSecond(host: String) = second.contains(host)
    override fun inMain(host: String) = main.contains(host)
    override fun inAi(host: String) = ai.contains(host)
    override fun inYoutube(host: String) = youtube.contains(host)
    override fun inTelegram(host: String) = telegram.contains(host)
    override fun inWhatsapp(host: String) = whatsapp.contains(host)
    override fun inDiscord(host: String) = discord.contains(host)
    override fun isCloudflareIp(ip: String) =
        ip.trim() in cloudflareIps || cloudflareRanges.any { it.contains(ip) }

    override fun isLocalTld(host: String): Boolean {
        val normalized = RoutingPolicy.normalizeHost(host) ?: return false
        return normalized.substringAfterLast('.') in localTlds
    }

    fun localTlds(): Set<String> = localTlds

    fun excludeDomains(): List<String> = exclude.domains()
    fun secondDomains(): List<String> = second.domains()
    fun mainDomains(): List<String> = main.domains()
    fun aiDomains(): List<String> = ai.domains()
    fun youtubeDomains(): List<String> = youtube.domains()
    fun telegramDomains(): List<String> = telegram.domains()
    fun whatsappDomains(): List<String> = whatsapp.domains()
    fun discordDomains(): List<String> = discord.domains()

    companion object {
        val EMPTY = RoutingListsSnapshot()
    }
}
