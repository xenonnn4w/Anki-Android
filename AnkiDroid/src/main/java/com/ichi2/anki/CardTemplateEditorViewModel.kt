/*
 * Copyright (c) 2025 Snowiee <xenonnn4w@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki

import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.CardOrdinal
import com.ichi2.anki.libanki.NoteTypeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class CardTemplateEditorViewModel : ViewModel() {
    private val _state = MutableStateFlow<CardTemplateEditorState>(CardTemplateEditorState.Loading)
    val state: StateFlow<CardTemplateEditorState> = _state.asStateFlow()

    /**
     * The current tempNotetype, or null if not yet loaded.
     * This is a convenience property that directly accesses the state.
     */
    val tempNotetype: CardTemplateNotetype?
        get() = (_state.value as? CardTemplateEditorState.Loaded)?.tempNotetype

    /**
     * Restores the tempNotetype from a saved instance state bundle.
     * Returns true if restoration was successful, false if the notetype needs to be loaded from DB.
     */
    fun restoreFromBundle(bundle: Bundle): Boolean {
        val restored = CardTemplateNotetype.fromBundle(bundle)
        if (restored != null) {
            Timber.d("Restored notetype from bundle: ${restored.notetype.name}")
            _state.value = CardTemplateEditorState.Loaded(tempNotetype = restored)
            return true
        }
        Timber.d("Could not restore notetype from bundle, will load from database")
        return false
    }

    /**
     * Loads the notetype from the collection and transitions to Loaded state.
     * Does nothing if tempNotetype is already set (i.e., already in Loaded state).
     */
    fun loadNotetype(noteTypeId: NoteTypeId) {
        if (_state.value is CardTemplateEditorState.Loaded) {
            Timber.d("Notetype already loaded, skipping")
            return
        }
        Timber.d("Loading notetype with id: $noteTypeId")
        viewModelScope.launch {
            try {
                val notetype =
                    withCol {
                        notetypes.clearCache()
                        notetypes.get(noteTypeId)!!.deepClone()
                    }
                val tempNotetype = CardTemplateNotetype(notetype)
                _state.value = CardTemplateEditorState.Loaded(tempNotetype = tempNotetype)
                Timber.d("Notetype loaded successfully: ${notetype.name}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load notetype")
                _state.value =
                    CardTemplateEditorState.Error(
                        CardTemplateEditorState.ReportableException(e),
                    )
            }
        }
    }

    /**
     * Transitions to the Finished state.
     * Called internally when the activity should close.
     */
    internal fun onFinish() {
        _state.value = CardTemplateEditorState.Finished
    }

    /**
     * Updates the current template ordinal.
     * Only valid when in Loaded state.
     */
    fun setCurrentTemplateOrd(ord: CardOrdinal) {
        updateLoadedState { it.copy(currentTemplateOrd = ord) }
    }

    /**
     * Updates the current editor view (front/back/styling).
     * Only valid when in Loaded state.
     */
    fun setCurrentEditorView(viewType: EditorViewType) {
        updateLoadedState { it.copy(currentEditorView = viewType) }
    }

    /**
     * Attempts to add a new template to the notetype.
     * Returns false if the notetype is cloze (cannot add templates).
     */
    fun addTemplate(): Boolean {
        val loadedState = _state.value as? CardTemplateEditorState.Loaded ?: return false
        val tempNotetype = loadedState.tempNotetype
        if (tempNotetype.notetype.isCloze) {
            Timber.w("Cannot add template to cloze notetype")
            updateLoadedState { it.copy(message = CardTemplateEditorState.UserMessage.CantAddTemplateToDynamic) }
            return false
        }
        Timber.d("Adding new template")
        return true
    }

    /**
     * Attempts to remove the template at the given ordinal.
     * Returns false if this is the last template.
     */
    fun removeTemplate(ord: CardOrdinal): Boolean {
        val loadedState = _state.value as? CardTemplateEditorState.Loaded ?: return false
        val tempNotetype = loadedState.tempNotetype
        if (tempNotetype.templateCount < 2) {
            Timber.w("Cannot delete last template")
            updateLoadedState { it.copy(message = CardTemplateEditorState.UserMessage.CantDeleteLastTemplate) }
            return false
        }
        Timber.d("Removing template at ord=$ord")
        tempNotetype.removeTemplate(ord)
        return true
    }

    /**
     * Saves the notetype to the database.
     * Uses CardTemplateNotetype.saveToDatabase() which handles template add/delete operations
     * followed by content updates atomically via undoableOp.
     */
    fun saveNotetype() {
        val loadedState =
            _state.value as? CardTemplateEditorState.Loaded ?: run {
                Timber.w("saveNotetype called but not in Loaded state")
                return
            }
        val tempNotetype = loadedState.tempNotetype
        Timber.d("Saving notetype: ${tempNotetype.notetype.name}")
        // Keep the loaded state but set isSaving flag via message (we use SaveSuccess after completion)
        viewModelScope.launch {
            try {
                tempNotetype.saveToDatabase()
                Timber.d("Notetype saved successfully")
                updateLoadedState { it.copy(message = CardTemplateEditorState.UserMessage.SaveSuccess) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to save notetype")
                _state.value =
                    CardTemplateEditorState.Error(
                        CardTemplateEditorState.ReportableException(e),
                    )
            }
        }
    }

    /**
     * Clears the current message.
     * Only valid when in Loaded state.
     */
    fun clearMessage() {
        updateLoadedState { it.copy(message = null) }
    }

    /**
     * Helper to update only when in Loaded state.
     * Ignores updates when in Loading, Error, or Finished states.
     */
    private inline fun updateLoadedState(transform: (CardTemplateEditorState.Loaded) -> CardTemplateEditorState.Loaded) {
        _state.update { currentState ->
            when (currentState) {
                is CardTemplateEditorState.Loaded -> transform(currentState)
                else -> currentState
            }
        }
    }
}
