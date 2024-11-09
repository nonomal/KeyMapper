package io.github.sds100.keymapper.sorting

import io.github.sds100.keymapper.data.Keys
import io.github.sds100.keymapper.data.repositories.PreferenceRepository
import io.github.sds100.keymapper.mappings.keymaps.KeyMapField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Observes the order in which key map fields should be sorted, prioritizing specific fields.
 * For example, if the order is [TRIGGER, ACTIONS, CONSTRAINTS, OPTIONS],
 * it means the key maps should be sorted first by trigger, then by actions, followed by constraints,
 * and finally by options.
 */
class ObserveKeyMapSortOrderUseCase(
    private val preferenceRepository: PreferenceRepository,
) {
    private val default by lazy {
        listOf(
            KeyMapField.TRIGGER,
            KeyMapField.ACTIONS,
            KeyMapField.CONSTRAINTS,
            KeyMapField.OPTIONS,
        )
    }

    operator fun invoke(): Flow<List<KeyMapField>> {
        return preferenceRepository
            .get(Keys.sortOrder)
            .map {
                val result = runCatching {
                    it
                        ?.split(",")
                        ?.map { KeyMapField.valueOf(it) }
                        ?: default
                }.getOrDefault(default).distinct()

                // If the result is not the expected size it means that the preference is corrupted
                if (result.size != 4) {
                    return@map default
                }

                result
            }
    }
}
