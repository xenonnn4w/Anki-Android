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
import com.ichi2.anki.libanki.CardTemplate
import com.ichi2.anki.libanki.CardTemplates
import com.ichi2.anki.libanki.NoteTypeId
import com.ichi2.anki.libanki.Notetypes
import com.ichi2.anki.libanki.utils.append
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber
import java.util.regex.Pattern

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
     * Adds a new template based on an existing template.
     * Copies the question/answer format from the source template.
     *
     * @param sourceTemplateOrd ordinal of the template to copy from
     * @return ordinal of the newly added template, -1 if failed
     */
    fun addNewTemplate(sourceTemplateOrd: CardOrdinal): Int {
        val loadedState = _state.value as? CardTemplateEditorState.Loaded ?: return -1
        val tempNotetype = loadedState.tempNotetype
        val notetype = tempNotetype.notetype

        if (notetype.isCloze) {
            Timber.w("Cannot add template to cloze notetype")
            updateLoadedState { it.copy(message = CardTemplateEditorState.UserMessage.CantAddTemplateToDynamic) }
            return -1
        }

        val templates = notetype.templates

        if (sourceTemplateOrd < 0 || sourceTemplateOrd >= templates.length()) {
            Timber.w("Invalid source template ordinal: $sourceTemplateOrd")
            return -1
        }

        val sourceTemplate = templates[sourceTemplateOrd]
        val newTemplate = Notetypes.newTemplate(generateNewCardName(templates))

        // Copy question & answer formats from source
        newTemplate.qfmt = sourceTemplate.qfmt
        newTemplate.afmt = sourceTemplate.afmt

        // Flip Q/A if this is the first additional template
        if (templates.length() == 1) {
            flipQA(newTemplate)
        }

        val lastExistingOrd = templates.last().ord
        newTemplate.setOrd(lastExistingOrd + 1)
        templates.append(newTemplate)
        tempNotetype.addNewTemplate(newTemplate)

        Timber.d("Added new template at ord=${newTemplate.ord}")
        updateLoadedState { it.copy(message = CardTemplateEditorState.UserMessage.TemplateAdded) }

        return templates.length() - 1
    }

    /**
     * Deletes a template from the notetype.
     *
     * @param templateOrd ordinal of the template to delete
     * @return true if deletion was successful
     */
    fun deleteTemplate(templateOrd: CardOrdinal): Boolean {
        val loadedState = _state.value as? CardTemplateEditorState.Loaded ?: return false
        val tempNotetype = loadedState.tempNotetype
        val notetype = tempNotetype.notetype

        if (tempNotetype.templateCount < 2) {
            Timber.w("Cannot delete last template")
            updateLoadedState { it.copy(message = CardTemplateEditorState.UserMessage.CantDeleteLastTemplate) }
            return false
        }

        val oldTemplates = notetype.templates
        val newTemplates = CardTemplates(JSONArray())

        for (template in oldTemplates) {
            if (template.ord != templateOrd) {
                newTemplates.append(template)
            } else {
                Timber.d("deleteTemplate() removing template with ord $templateOrd")
                tempNotetype.removeTemplate(template.ord)
            }
        }

        notetype.templates = newTemplates
        Notetypes._updateTemplOrds(notetype)

        Timber.d("Template deleted at ord=$templateOrd")
        updateLoadedState { it.copy(message = CardTemplateEditorState.UserMessage.TemplateDeleted) }

        return true
    }

    /**
     * Renames a template.
     *
     * @param templateOrd ordinal of the template to rename
     * @param newName the new name for the template
     * @return true if rename was successful
     */
    fun renameTemplate(
        templateOrd: CardOrdinal,
        newName: String,
    ): Boolean {
        val loadedState = _state.value as? CardTemplateEditorState.Loaded ?: return false
        val tempNotetype = loadedState.tempNotetype
        val template = tempNotetype.getTemplate(templateOrd)

        template.name = newName
        Timber.d("Renamed template $templateOrd to '$newName'")
        updateLoadedState { it.copy(message = CardTemplateEditorState.UserMessage.TemplateRenamed) }

        return true
    }

    /**
     * Generates a unique name for a new card template.
     */
    private fun generateNewCardName(templates: CardTemplates): String {
        var n = templates.length() + 1
        while (true) {
            val name = CollectionManager.TR.cardTemplatesCard(n)
            if (templates.all { name != it.name }) {
                return name
            }
            n += 1
        }
    }

    /**
     * Flips the question and answer sides of a template.
     * Used when adding a second template to create a reversed card.
     */
    private fun flipQA(template: CardTemplate) {
        val qfmt = template.qfmt
        val afmt = template.afmt
        val pattern = Pattern.compile("(?s)(.+)<hr id=answer>(.+)")
        val matcher = pattern.matcher(afmt)
        template.qfmt =
            if (!matcher.find()) {
                afmt.replace("{{FrontSide}}", "")
            } else {
                matcher.group(2)!!.trim()
            }
        template.afmt = "{{FrontSide}}\n\n<hr id=answer>\n\n$qfmt"
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
