package com.example.nova

/** Слоты, между которыми распределяет трафик RoutingPolicy. */
enum class SlotKind { PRIMARY, SECONDARY, RELAY, DIRECT }

data class SlotState(
    val kind: SlotKind,
    val alive: Boolean,
    /** Страна выхода (ISO-код), если известна. */
    val country: String? = null,
    /** Вторичный слот занят Tor: видео через него не пускаем. */
    val isTor: Boolean = false,
)

/**
 * Превращает класс маршрута в цепочку слотов — аналог строки PAC.
 *
 * Политика отвечает «куда по правилам», резолвер — «чем это можно доставить
 * прямо сейчас». Разделение позволяет менять здоровье слотов, не пересчитывая
 * правила, и наоборот.
 */
object SlotResolver {

    fun chainFor(
        route: RouteClass,
        slots: Map<SlotKind, SlotState>,
        killSwitch: Boolean = false,
    ): List<SlotKind> {
        val primary = slots[SlotKind.PRIMARY]?.takeIf { it.alive }
        val secondary = slots[SlotKind.SECONDARY]?.takeIf { it.alive }
        val relay = slots[SlotKind.RELAY]?.takeIf { it.alive }
        val lastResort = if (killSwitch) emptyList() else listOf(SlotKind.DIRECT)

        return when (route) {
            RouteClass.DIRECT -> listOf(SlotKind.DIRECT)
            RouteClass.BLOCK -> emptyList()

            RouteClass.RU -> buildList {
                primary?.let { add(SlotKind.PRIMARY) }
                secondary?.let { add(SlotKind.SECONDARY) }
                addAll(lastResort)
            }

            RouteClass.EU -> buildList {
                if (primary != null && isAbroad(primary)) add(SlotKind.PRIMARY)
                secondary?.let { add(SlotKind.SECONDARY) }
                if (primary != null && !isAbroad(primary)) add(SlotKind.PRIMARY)
                addAll(lastResort)
            }

            RouteClass.YOUTUBE -> buildList {
                // Видео через Tor нецелесообразно: если вторичный — Tor,
                // первым идёт primary.
                val secondaryFirst = secondary != null &&
                    !secondary.isTor &&
                    (primary == null || !isAbroad(primary))
                if (secondaryFirst) {
                    add(SlotKind.SECONDARY)
                    primary?.let { add(SlotKind.PRIMARY) }
                } else {
                    primary?.let { add(SlotKind.PRIMARY) }
                    secondary?.let { add(SlotKind.SECONDARY) }
                }
                addAll(lastResort)
            }

            RouteClass.TELEGRAM -> buildList {
                relay?.let { add(SlotKind.RELAY) }
                // Pinned egress: без подмены на соседний слот и без DIRECT.
                if (relay == null) primary?.let { add(SlotKind.PRIMARY) }
            }

            RouteClass.WHATSAPP, RouteClass.DISCORD -> buildList {
                primary?.let { add(SlotKind.PRIMARY) }
                secondary?.let { add(SlotKind.SECONDARY) }
                addAll(lastResort)
            }

            RouteClass.AI -> buildList {
                secondary?.let { add(SlotKind.SECONDARY) }
                primary?.let { add(SlotKind.PRIMARY) }
                addAll(lastResort)
            }

            RouteClass.GOOGLE_AI -> buildList {
                secondary?.let { add(SlotKind.SECONDARY) }
                primary?.let { add(SlotKind.PRIMARY) }
                addAll(lastResort)
            }
        }
    }

    fun isAbroad(slot: SlotState): Boolean {
        val country = slot.country?.trim()?.uppercase() ?: return false
        return country.isNotEmpty() && country != "RU"
    }
}
