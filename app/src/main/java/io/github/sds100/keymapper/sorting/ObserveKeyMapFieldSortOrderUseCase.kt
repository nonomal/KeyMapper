package io.github.sds100.keymapper.sorting

import io.github.sds100.keymapper.data.Keys
import io.github.sds100.keymapper.data.repositories.PreferenceRepository
import io.github.sds100.keymapper.mappings.keymaps.KeyMapField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Observes the sorting order of a key map field, indicating the order in which the specified field
 * should be sorted.
 */
class ObserveKeyMapFieldSortOrderUseCase(
    private val preferenceRepository: PreferenceRepository,
) {
    /**
     * Observe the sorting order of the key map field.
     *
     * @param field The key map field to observe.
     * @return A flow of the sorting order.
     */
    operator fun invoke(field: KeyMapField): Flow<SortOrder> {
        val key = when (field) {
            KeyMapField.TRIGGER -> Keys.sortTriggerAscending
            KeyMapField.ACTIONS -> Keys.sortActionsAscending
            KeyMapField.CONSTRAINTS -> Keys.sortConstraintsAscending
            KeyMapField.OPTIONS -> Keys.sortOptionsAscending
        }

        return preferenceRepository.get(key).map {
            when (it) {
                // Default value is ascending
                null, true -> SortOrder.ASCENDING
                false -> SortOrder.DESCENDING
            }
        }
    }
}
