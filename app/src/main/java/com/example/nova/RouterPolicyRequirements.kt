package com.example.nova

/**
 * Чистая политика выбора AUTO: какие возможности требуют nova-router.
 *
 * Наличие всегда генерируемых defaultOrder/exclude/local-tld здесь не
 * учитывается: они есть в любом RouterConfig и сами по себе не должны
 * принудительно включать MULTI.
 */
object RouterPolicyRequirements {

    data class Requirements(
        val requiresMulti: Boolean,
        val reasons: List<String>,
        val unsupportedInGoCore: List<String>,
    )

    fun evaluate(
        smartRouting: Boolean,
        overrides: Map<String, String>,
        killSwitch: Boolean,
    ): Requirements {
        val reasons = buildList {
            if (smartRouting) add("smart-routing")
            overrides.toSortedMap().forEach { (service, value) ->
                if (value.isNotBlank() && value != RouteSettings.AUTO) {
                    add("override:$service=$value")
                }
            }
            if (killSwitch) add("kill-switch")
        }
        val unsupported = buildList {
            if (smartRouting) add("router-policies")
            if (overrides.any { it.value.isNotBlank() && it.value != RouteSettings.AUTO }) {
                add("service-overrides")
            }
            if (killSwitch) add("kill-switch")
        }.distinct()
        return Requirements(
            requiresMulti = reasons.isNotEmpty(),
            reasons = reasons,
            unsupportedInGoCore = unsupported,
        )
    }

    /**
     * Подпись data plane для UI. Только факт, без обещаний:
     * MULTI — router, Go-core — только core-правила, single — один транспорт.
     */
    fun dataPlaneHint(backend: String, killSwitch: Boolean = false): String {
        val normalized = backend.trim().uppercase()
        val base = when {
            normalized == NovaVpnService.BACKEND_MULTI ->
                "MULTI: активны правила router"
            normalized.startsWith(NovaVpnService.BACKEND_WARP) ->
                "Go-core: правила router не применяются"
            normalized.startsWith(NovaVpnService.BACKEND_OPERA) ||
                normalized.startsWith(NovaVpnService.BACKEND_VLESS) ||
                normalized.startsWith(NovaVpnService.BACKEND_TOR) ||
                normalized.startsWith(NovaVpnService.BACKEND_DNS) ->
                "Один транспорт: правила router не применяются"
            else -> ""
        }
        if (base.isEmpty()) return ""
        if (killSwitch && normalized != NovaVpnService.BACKEND_MULTI) {
            return "$base; внимание: Kill Switch маршрутизатора не действует"
        }
        return base
    }
}
