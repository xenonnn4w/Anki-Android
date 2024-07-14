/***************************************************************************************
 *                                                                                      *
 * Copyright (c) 2012 Norbert Nagold <norbert.nagold@gmail.com>                         *
 * Copyright (c) 2014 Timothy Rae <perceptualchaos2@gmail.com>                          *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.anki

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.View.OnFocusChangeListener
import android.view.ViewGroup.MarginLayoutParams
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import anki.config.ConfigKey
import anki.notes.NoteFieldsCheckResponse
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.ichi2.anim.ActivityTransitionAnimation
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.bottomsheet.ImageOcclusionBottomSheetFragment
import com.ichi2.anki.dialogs.ConfirmationDialog
import com.ichi2.anki.dialogs.DeckSelectionDialog.DeckSelectionListener
import com.ichi2.anki.dialogs.DeckSelectionDialog.SelectableDeck
import com.ichi2.anki.dialogs.DiscardChangesDialog
import com.ichi2.anki.dialogs.IntegerDialog
import com.ichi2.anki.dialogs.tags.TagsDialog
import com.ichi2.anki.dialogs.tags.TagsDialogFactory
import com.ichi2.anki.dialogs.tags.TagsDialogListener
import com.ichi2.anki.model.CardStateFilter
import com.ichi2.anki.multimediacard.IMultimediaEditableNote
import com.ichi2.anki.multimediacard.activity.MultimediaEditFieldActivity
import com.ichi2.anki.multimediacard.activity.MultimediaEditFieldActivityExtra
import com.ichi2.anki.multimediacard.fields.*
import com.ichi2.anki.multimediacard.impl.MultimediaEditableNote
import com.ichi2.anki.noteeditor.CustomToolbarButton
import com.ichi2.anki.noteeditor.FieldState
import com.ichi2.anki.noteeditor.FieldState.FieldChangeType
import com.ichi2.anki.noteeditor.Toolbar
import com.ichi2.anki.noteeditor.Toolbar.TextFormatListener
import com.ichi2.anki.noteeditor.Toolbar.TextWrapper
import com.ichi2.anki.pages.ImageOcclusion
import com.ichi2.anki.preferences.sharedPrefs
import com.ichi2.anki.previewer.TemplatePreviewerArguments
import com.ichi2.anki.previewer.TemplatePreviewerFragment
import com.ichi2.anki.receiver.SdCardReceiver
import com.ichi2.anki.servicelayer.LanguageHintService
import com.ichi2.anki.servicelayer.NoteService
import com.ichi2.anki.snackbar.BaseSnackbarBuilderProvider
import com.ichi2.anki.snackbar.SnackbarBuilder
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.ui.setupNoteTypeSpinner
import com.ichi2.anki.utils.ext.isImageOcclusion
import com.ichi2.anki.utils.getTimestamp
import com.ichi2.anki.widgets.DeckDropDownAdapter.SubtitleListener
import com.ichi2.annotations.NeedsTest
import com.ichi2.compat.CompatHelper.Companion.getSerializableCompat
import com.ichi2.compat.CompatHelper.Companion.registerReceiverCompat
import com.ichi2.libanki.*
import com.ichi2.libanki.Collection
import com.ichi2.libanki.Decks.Companion.CURRENT_DECK
import com.ichi2.libanki.Note.ClozeUtils
import com.ichi2.libanki.Notetypes.Companion.NOT_FOUND_NOTE_TYPE
import com.ichi2.libanki.utils.TimeManager
import com.ichi2.utils.*
import com.ichi2.utils.IntentUtil.resolveMimeType
import com.ichi2.widget.WidgetStatus
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.*
import java.util.function.Consumer
import kotlin.collections.ArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Allows the user to edit a note, for instance if there is a typo. A card is a presentation of a note, and has two
 * sides: a question and an answer. Any number of fields can appear on each side. When you add a note to Anki, cards
 * which show that note are generated. Some models generate one card, others generate more than one.
 *
 * @see [the Anki Desktop manual](https://docs.ankiweb.net/getting-started.html.cards)
 */
@KotlinCleanup("Go through the class and select elements to fix")
@KotlinCleanup("see if we can lateinit")
class NoteEditor : AnkiActivity(), DeckSelectionListener, SubtitleListener, TagsDialogListener, BaseSnackbarBuilderProvider {
    /** Whether any change are saved. E.g. multimedia, new card added, field changed and saved. */
    private var changed = false
    private var isTagsEdited = false
    private var isFieldEdited = false

    /**
     * Flag which forces the calling activity to rebuild it's definition of current card from scratch
     */
    private var reloadRequired = false

    /**
     * Broadcast that informs us when the sd card is about to be unmounted
     */
    private var unmountReceiver: BroadcastReceiver? = null
    private var fieldsLayoutContainer: LinearLayout? = null
    private var mediaRegistration: MediaRegistration? = null
    private var tagsDialogFactory: TagsDialogFactory? = null
    private var tagsButton: AppCompatButton? = null
    private var cardsButton: AppCompatButton? = null

    @VisibleForTesting
    internal var noteTypeSpinner: Spinner? = null
    private var deckSpinnerSelection: DeckSpinnerSelection? = null
    private var imageOcclusionButtonsContainer: LinearLayout? = null
    private var selectImageForOcclusionButton: Button? = null
    private var editOcclusionsButton: Button? = null
    private var pasteOcclusionImageButton: Button? = null

    // non-null after onCollectionLoaded
    private var editorNote: Note? = null

    private var currentImageOccPath: String? = null

    /* Null if adding a new card. Presently NonNull if editing an existing note - but this is subject to change */
    private var currentEditedCard: Card? = null
    private var selectedTags: MutableList<String>? = null

    @get:VisibleForTesting
    var deckId: DeckId = 0
        private set
    private var allModelIds: List<Long>? = null

    @KotlinCleanup("this ideally should be Int, Int?")
    private var modelChangeFieldMap: MutableMap<Int, Int>? = null
    private var modelChangeCardMap: HashMap<Int, Int?>? = null
    private val customViewIds = ArrayList<Int>()

    /* indicates if a new note is added or a card is edited */
    private var addNote = false
    private var aedictIntent = false

    /* indicates which activity called Note Editor */
    private var caller = 0
    private var editFields: LinkedList<FieldEditText?>? = null
    private var sourceText: Array<String?>? = null
    private val fieldState = FieldState.fromEditor(this)
    private lateinit var toolbar: Toolbar

    // Use the same HTML if the same image is pasted multiple times.
    private var pastedImageCache: HashMap<String, String> = HashMap()

    // save field index as key and text as value when toggle sticky clicked in Field Edit Text
    private var toggleStickyText: HashMap<Int, String?> = HashMap()
    private val onboarding = Onboarding.NoteEditor(this)

    var clipboard: ClipboardManager? = null

    private val requestAddLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        NoteEditorActivityResultCallback {
            if (it.resultCode != RESULT_CANCELED) {
                changed = true
            }
        }
    )

    private val requestMultiMediaEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        @NeedsTest("test to guard against changes in the REQUEST_MULTIMEDIA_EDIT clause preventing text fields to be updated")
        NoteEditorActivityResultCallback { result ->
            if (result.resultCode != RESULT_CANCELED) {
                val col = getColUnsafe
                val extras = result.data!!.extras ?: return@NoteEditorActivityResultCallback
                val index = extras.getInt(MultimediaEditFieldActivity.EXTRA_RESULT_FIELD_INDEX)
                val field = extras.getSerializableCompat<IField>(MultimediaEditFieldActivity.EXTRA_RESULT_FIELD) ?: return@NoteEditorActivityResultCallback
                if (field.type != EFieldType.TEXT && (field.imagePath == null && field.audioPath == null)) {
                    Timber.i("field imagePath and audioPath are both null")
                    return@NoteEditorActivityResultCallback
                }
                val note = getCurrentMultimediaEditableNote(col)
                note.setField(index, field)
                val fieldEditText = editFields!![index]
                // Import field media
                // This goes before setting formattedValue to update
                // media paths with the checksum when they have the same name
                NoteService.importMediaToDirectory(col, field)
                // Completely replace text for text fields (because current text was passed in)
                val formattedValue = field.formattedValue
                if (field.type === EFieldType.TEXT) {
                    fieldEditText!!.setText(formattedValue)
                } else if (fieldEditText!!.text != null) {
                    insertStringInField(fieldEditText, formattedValue)
                }
                changed = true
            }
        }
    )

    private val requestTemplateEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        NoteEditorActivityResultCallback {
            // Model can change regardless of exit type - update ourselves and CardBrowser
            reloadRequired = true
            editorNote!!.notetype = getColUnsafe.notetypes.get(editorNote!!.mid)!!
            if (currentEditedCard == null || !editorNote!!.cardIds(getColUnsafe)
                .contains(currentEditedCard!!.id)
            ) {
                if (!addNote) {
                    /* This can occur, for example, if the
                     * card type was deleted or if the note
                     * type was changed without moving this
                     * card to another type. */
                    Timber.d("onActivityResult() template edit return - current card is gone, close note editor")
                    showSnackbar(getString(R.string.template_for_current_card_deleted))
                    closeNoteEditor()
                } else {
                    Timber.d("onActivityResult() template edit return, in add mode, just re-display")
                }
            } else {
                Timber.d("onActivityResult() template edit return - current card exists")
                // reload current card - the template ordinals are possibly different post-edit
                currentEditedCard = getColUnsafe.getCard(currentEditedCard!!.id)
                updateCards(editorNote!!.notetype)
            }
        }
    )

    private val ioEditorLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            ImportUtils.getFileCachedCopy(this@NoteEditor, uri)?.let { path ->
                setupImageOcclusionEditor(path)
            }
        }
    }

    private val requestIOEditorCloser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        NoteEditorActivityResultCallback { result ->
            if (result.resultCode != RESULT_CANCELED) {
                changed = true
                if (!addNote) {
                    reloadRequired = true
                    closeNoteEditor(RESULT_UPDATED_IO_NOTE, null)
                }
            }
        }
    )

    private inner class NoteEditorActivityResultCallback(private val callback: (result: ActivityResult) -> Unit) : ActivityResultCallback<ActivityResult> {
        override fun onActivityResult(result: ActivityResult) {
            Timber.d("onActivityResult() with result: %s", result.resultCode)
            if (result.resultCode == DeckPicker.RESULT_DB_ERROR) {
                closeNoteEditor(DeckPicker.RESULT_DB_ERROR, null)
            }
            callback(result)
        }
    }

    override fun onDeckSelected(deck: SelectableDeck?) {
        if (deck == null) {
            return
        }
        deckId = deck.deckId
        // this is called because DeckSpinnerSelection.onDeckAdded doesn't update the list
        deckSpinnerSelection!!.initializeNoteEditorDeckSpinner(getColUnsafe)
        launchCatchingTask {
            deckSpinnerSelection!!.selectDeckById(deckId, false)
        }
    }

    override val subtitleText: String
        get() = ""

    private enum class AddClozeType {
        SAME_NUMBER, INCREMENT_NUMBER
    }

    @VisibleForTesting
    var addNoteErrorMessage: String? = null

    private fun displayErrorSavingNote() {
        val errorMessage = snackbarErrorText
        // Anki allows to proceed in case we try to add non cloze text in cloze field with warning,
        // this snackbar helps replicate similar behaviour
        if (errorMessage == TR.addingYouHaveAClozeDeletionNote()) {
            noClozeDialog(errorMessage)
        } else {
            showSnackbar(errorMessage)
        }
    }

    private fun noClozeDialog(errorMessage: String) {
        AlertDialog.Builder(this).show {
            message(text = errorMessage)
            positiveButton(text = TR.actionsSave()) {
                lifecycleScope.launch {
                    saveNoteWithProgress()
                }
            }
            negativeButton(R.string.dialog_cancel)
        }
    }

    @VisibleForTesting
    val snackbarErrorText: String
        get() = when {
            addNoteErrorMessage != null -> addNoteErrorMessage!!
            allFieldsHaveContent() -> resources.getString(R.string.note_editor_no_cards_created_all_fields)
            else -> resources.getString(R.string.note_editor_no_cards_created)
        }

    override val baseSnackbarBuilder: SnackbarBuilder = {
        if (sharedPrefs().getBoolean(PREF_NOTE_EDITOR_SHOW_TOOLBAR, true)) {
            anchorView = findViewById<Toolbar>(R.id.editor_toolbar)
        }
    }

    private fun allFieldsHaveContent() = currentFieldStrings.none { it.isNullOrEmpty() }

    // ----------------------------------------------------------------------------
    // ANDROID METHODS
    // ----------------------------------------------------------------------------
    @KotlinCleanup("fix suppress")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        tagsDialogFactory = TagsDialogFactory(this).attachToActivity<TagsDialogFactory>(this)
        mediaRegistration = MediaRegistration(this)
        super.onCreate(savedInstanceState)
        if (!ensureStoragePermissions()) {
            return
        }
        fieldState.setInstanceState(savedInstanceState)
        setContentView(R.layout.note_editor)
        val intent = intent
        if (savedInstanceState != null) {
            caller = savedInstanceState.getInt("caller")
            addNote = savedInstanceState.getBoolean("addNote")
            deckId = savedInstanceState.getLong("did")
            selectedTags = savedInstanceState.getStringArrayList("tags")
            reloadRequired = savedInstanceState.getBoolean(RELOAD_REQUIRED_EXTRA_KEY)
            pastedImageCache =
                savedInstanceState.getSerializableCompat<HashMap<String, String>>("imageCache")!!
            toggleStickyText =
                savedInstanceState.getSerializableCompat<HashMap<Int, String?>>("toggleSticky")!!
            changed = savedInstanceState.getBoolean(NOTE_CHANGED_EXTRA_KEY)
        } else {
            if (intentLaunchedWithImage(intent)) {
                Timber.i("Intent contained an image")
                intent.putExtra(EXTRA_CALLER, CALLER_ADD_IMAGE)
            }
            caller = intent.getIntExtra(EXTRA_CALLER, CALLER_NO_CALLER)
            if (caller == CALLER_NO_CALLER) {
                val action = intent.action
                if (ACTION_CREATE_FLASHCARD == action || ACTION_CREATE_FLASHCARD_SEND == action || Intent.ACTION_PROCESS_TEXT == action) {
                    caller = CALLER_NOTEEDITOR_INTENT_ADD
                }
            }
        }
        // Set up toolbar
        toolbar = findViewById(R.id.editor_toolbar)
        toolbar.apply {
            formatListener = TextFormatListener { formatter: Toolbar.TextFormatter ->
                val currentFocus = currentFocus as? FieldEditText ?: return@TextFormatListener
                modifyCurrentSelection(formatter, currentFocus)
            }
            // Sets the background and icon color of toolbar respectively.
            setBackgroundColor(
                MaterialColors.getColor(
                    this@NoteEditor,
                    R.attr.toolbarBackgroundColor,
                    0
                )
            )
            setIconColor(MaterialColors.getColor(this@NoteEditor, R.attr.toolbarIconColor, 0))
        }
        val mainView = findViewById<View>(android.R.id.content)
        // Enable toolbar
        enableToolbar(mainView)
        startLoadingCollection()
        onboarding.onCreate()
        // TODO this callback doesn't handle predictive back navigation!
        // see #14678, added to temporarily fix for a bug
        onBackPressedDispatcher.addCallback(this) {
            Timber.i("NoteEditor:: onBackPressed()")
            closeCardEditorWithCheck()
        }

        setNavigationBarColor(R.attr.toolbarBackgroundColor)
    }

    @NeedsTest("Test when the user directly passes image to the edit note field")
    private fun handleImageIntent(data: Intent) {
        val imageUri = if (data.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(data, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            data.data
        }
        Timber.d("Image Uri : $imageUri")
        // ImageIntentManager.saveImageUri(imageUri)
        // the field won't exist so it will always be a new card
        val note = getCurrentMultimediaEditableNote(getColUnsafe)
        startMultimediaFieldEditor(0, note, imageUri)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        addInstanceStateToBundle(outState)
        super.onSaveInstanceState(outState)
    }

    private fun addInstanceStateToBundle(savedInstanceState: Bundle) {
        Timber.i("Saving instance")
        savedInstanceState.putInt("caller", caller)
        savedInstanceState.putBoolean("addNote", addNote)
        savedInstanceState.putLong("did", deckId)
        savedInstanceState.putBoolean(NOTE_CHANGED_EXTRA_KEY, changed)
        savedInstanceState.putBoolean(RELOAD_REQUIRED_EXTRA_KEY, reloadRequired)
        savedInstanceState.putIntegerArrayList("customViewIds", customViewIds)
        savedInstanceState.putSerializable("imageCache", pastedImageCache)
        savedInstanceState.putSerializable("toggleSticky", toggleStickyText)
        if (selectedTags == null) {
            selectedTags = ArrayList(0)
        }
        savedInstanceState.putStringArrayList("tags", selectedTags?.let { ArrayList(it) })
    }

    private val fieldsAsBundleForPreview: Bundle
        get() = NoteService.getFieldsAsBundleForPreview(editFields, shouldReplaceNewlines())

    // Finish initializing the activity after the collection has been correctly loaded
    override fun onCollectionLoaded(col: Collection) {
        super.onCollectionLoaded(col)
        val intent = intent
        Timber.d("NoteEditor() onCollectionLoaded: caller: %d", caller)
        registerExternalStorageListener()
        fieldsLayoutContainer = findViewById(R.id.CardEditorEditFieldsLayout)
        tagsButton = findViewById(R.id.CardEditorTagButton)
        cardsButton = findViewById(R.id.CardEditorCardsButton)
        cardsButton!!.setOnClickListener {
            Timber.i("NoteEditor:: Cards button pressed. Opening template editor")
            showCardTemplateEditor()
        }
        imageOcclusionButtonsContainer = findViewById(R.id.ImageOcclusionButtonsLayout)
        editOcclusionsButton = findViewById(R.id.EditOcclusionsButton)
        selectImageForOcclusionButton = findViewById(R.id.SelectImageForOcclusionButton)
        pasteOcclusionImageButton = findViewById(R.id.PasteImageForOcclusionButton)

        try {
            clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        } catch (e: Exception) {
            Timber.w(e)
        }

        aedictIntent = false
        currentEditedCard = null
        when (caller) {
            CALLER_NO_CALLER -> {
                Timber.e("no caller could be identified, closing")
                finish()
                return
            }
            CALLER_EDIT -> {
                val cardId = requireNotNull(intent.extras?.getLong(EXTRA_CARD_ID)) { "EXTRA_CARD_ID" }
                currentEditedCard = col.getCard(cardId)
                editorNote = currentEditedCard!!.note(col)
                addNote = false
            }
            CALLER_PREVIEWER_EDIT -> {
                val id = intent.extras?.getLong(EXTRA_EDIT_FROM_CARD_ID)
                    ?: throw IllegalArgumentException("null EXTRA_EDIT_FROM_CARD_ID")
                currentEditedCard = col.getCard(id)
                editorNote = currentEditedCard!!.note(getColUnsafe)
            }
            CALLER_STUDYOPTIONS, CALLER_DECKPICKER, CALLER_REVIEWER_ADD, CALLER_CARDBROWSER_ADD, CALLER_NOTEEDITOR ->
                addNote = true
            CALLER_NOTEEDITOR_INTENT_ADD, INSTANT_NOTE_EDITOR -> {
                fetchIntentInformation(intent)
                if (sourceText == null) {
                    finish()
                    return
                }
                if ("Aedict Notepad" == sourceText!![0] && addFromAedict(sourceText!![1])) {
                    finish()
                    return
                }
                addNote = true
            }
            // image occlusion is handled at the end of this method, grep: CALLER_IMG_OCCLUSION
            // we need to have loaded the current note type
            CALLER_IMG_OCCLUSION, CALLER_ADD_IMAGE -> {
                addNote = true
            }
            else -> {}
        }

        if (addNote) {
            editOcclusionsButton?.visibility = View.GONE
            selectImageForOcclusionButton?.setOnClickListener {
                val imageOcclusionBottomSheet = ImageOcclusionBottomSheetFragment()
                imageOcclusionBottomSheet.listener =
                    object : ImageOcclusionBottomSheetFragment.ImagePickerListener {
                        override fun onCameraClicked() {
                            dispatchCameraEvent()
                        }

                        override fun onGalleryClicked() {
                            ioEditorLauncher.launch("image/*")
                        }
                    }
                imageOcclusionBottomSheet.show(
                    supportFragmentManager,
                    "ImageOcclusionBottomSheetFragment"
                )
            }

            pasteOcclusionImageButton?.text = TR.notetypesIoPasteImageFromClipboard()
            pasteOcclusionImageButton?.setOnClickListener {
                // TODO: Support all extensions
                //  See https://github.com/ankitects/anki/blob/6f3550464d37aee1b8b784e431cbfce8382d3ce7/rslib/src/image_occlusion/imagedata.rs#L154
                if (ClipboardUtil.hasImage(clipboard)) {
                    val uri = ClipboardUtil.getImageUri(clipboard)
                    val i = Intent().apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newUri(contentResolver, uri.toString(), uri)
                    }
                    ImportUtils.getFileCachedCopy(this, i)?.let { path ->
                        setupImageOcclusionEditor(path)
                    }
                } else {
                    showSnackbar(TR.editingNoImageFoundOnClipboard())
                }
            }
        } else {
            selectImageForOcclusionButton?.visibility = View.GONE
            pasteOcclusionImageButton?.visibility = View.GONE
            editOcclusionsButton?.visibility = View.VISIBLE
            editOcclusionsButton?.text = resources.getString(R.string.edit_occlusions)
            editOcclusionsButton?.setOnClickListener {
                setupImageOcclusionEditor()
            }
        }

        // Note type Selector
        noteTypeSpinner = findViewById(R.id.note_type_spinner)
        allModelIds = setupNoteTypeSpinner(this, noteTypeSpinner!!, col)

        // Deck Selector
        val deckTextView = findViewById<TextView>(R.id.CardEditorDeckText)
        // If edit mode and more than one card template distinguish between "Deck" and "Card deck"
        if (!addNote && editorNote!!.notetype.getJSONArray("tmpls").length() > 1) {
            deckTextView.setText(R.string.CardEditorCardDeck)
        }
        deckSpinnerSelection =
            DeckSpinnerSelection(
                this,
                findViewById(R.id.note_deck_spinner),
                showAllDecks = false,
                alwaysShowDefault = true,
                showFilteredDecks = false
            )
        deckSpinnerSelection!!.initializeNoteEditorDeckSpinner(col)
        deckId = intent.getLongExtra(EXTRA_DID, deckId)
        val getTextFromSearchView = intent.getStringExtra(EXTRA_TEXT_FROM_SEARCH_VIEW)
        setDid(editorNote)
        setNote(editorNote, FieldChangeType.onActivityCreation(shouldReplaceNewlines()))
        if (addNote) {
            noteTypeSpinner!!.onItemSelectedListener = SetNoteTypeListener()
            setTitle(R.string.menu_add)
            // set information transferred by intent
            var contents: String? = null
            val tags = intent.getStringArrayExtra(EXTRA_TAGS)

            try {
                // If content has been shared, we can't share to an image occlusion note type
                if (currentNotetypeIsImageOcclusion() && (sourceText != null || caller == CALLER_ADD_IMAGE)) {
                    val model = col.notetypes.all().first {
                        !it.isImageOcclusion
                    }
                    changeNoteType(model.id)
                }
            } catch (e: NoSuchElementException) {
                showSnackbar(R.string.missing_note_type)
                // setting the text to null & caller to CALLER_NO_CALLER would skip adding text/image to edit field
                sourceText = null
                caller = CALLER_NO_CALLER
                Timber.w(e)
            }

            if (sourceText != null) {
                if (aedictIntent && editFields!!.size == 3 && sourceText!![1]!!.contains("[")) {
                    contents = sourceText!![1]!!
                        .replaceFirst("\\[".toRegex(), "\u001f" + sourceText!![0] + "\u001f")
                    contents = contents.substring(0, contents.length - 1)
                } else if (!editFields!!.isEmpty()) {
                    editFields!![0]!!.setText(sourceText!![0])
                    if (editFields!!.size > 1) {
                        editFields!![1]!!.setText(sourceText!![1])
                    }
                }
            } else {
                contents = intent.getStringExtra(EXTRA_CONTENTS)
            }
            contents?.let { setEditFieldTexts(it) }
            tags?.let { setTags(it) }
            if (caller == CALLER_ADD_IMAGE) handleImageIntent(intent)
        } else {
            noteTypeSpinner!!.onItemSelectedListener = EditNoteTypeListener()
            setTitle(R.string.cardeditor_title_edit_card)
        }
        findViewById<View>(R.id.CardEditorTagButton).setOnClickListener {
            Timber.i("NoteEditor:: Tags button pressed... opening tags editor")
            showTagsDialog()
        }
        if (!addNote && currentEditedCard != null) {
            Timber.i(
                "onCollectionLoaded() Edit note activity successfully started with card id %d",
                currentEditedCard!!.id
            )
        }
        if (addNote) {
            Timber.i(
                "onCollectionLoaded() Edit note activity successfully started in add card mode with node id %d",
                editorNote!!.id
            )
        }

        // don't open keyboard if not adding note
        if (!addNote) {
            this.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }

        // set focus to FieldEditText 'first' on startup like Anki desktop
        if (editFields != null && !editFields!!.isEmpty()) {
            // EXTRA_TEXT_FROM_SEARCH_VIEW takes priority over other intent inputs
            if (!getTextFromSearchView.isNullOrEmpty()) {
                editFields!!.first()!!.setText(getTextFromSearchView)
            }
            editFields!!.first()!!.requestFocus()
        }

        if (caller == CALLER_IMG_OCCLUSION) {
            // val saveImageUri = ImageIntentManager.getImageUri()
            val saveImageUri = IntentCompat.getParcelableExtra(intent, EXTRA_IMG_OCCLUSION, Uri::class.java)
            if (saveImageUri != null) {
                ImportUtils.getFileCachedCopy(this, saveImageUri)?.let { path ->
                    setupImageOcclusionEditor(path)
                }
            } else {
                Timber.w("Image uri is null")
            }
        }
    }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { isPictureTaken ->
            if (isPictureTaken) {
                currentImageOccPath?.let { imagePath ->
                    val photoFile = File(imagePath)
                    val imageUri: Uri = FileProvider.getUriForFile(
                        this,
                        this.applicationContext.packageName + ".apkgfileprovider",
                        photoFile
                    )
                    startCrop(imageUri)
                }
            } else {
                Timber.d("Camera aborted or some interruption")
            }
        }

    private fun startCrop(imageUri: Uri) {
        ImageUtils.cropImage(activityResultRegistry, imageUri) { result ->
            if (result != null && result.isSuccessful) {
                val uriFilePath = result.getUriFilePath(this)
                uriFilePath?.let { setupImageOcclusionEditor(it) }
            } else {
                Timber.v("Unable to crop the image")
            }
        }
    }

    private fun dispatchCameraEvent() {
        val photoFile: File? = try {
            createImageFile()
        } catch (e: Exception) {
            Timber.w("Error creating the file", e)
            return
        }
        photoFile?.let {
            val photoURI: Uri = FileProvider.getUriForFile(
                this,
                this.applicationContext.packageName + ".apkgfileprovider",
                it
            )
            cameraLauncher.launch(photoURI)
        }
    }

    private fun createImageFile(): File {
        val currentDateTime = getTimestamp(TimeManager.time)
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "ANKIDROID_$currentDateTime",
            ".jpg",
            storageDir
        ).apply {
            currentImageOccPath = absolutePath
        }
    }

    private fun modifyCurrentSelection(formatter: Toolbar.TextFormatter, textBox: FieldEditText) {
        // get the current text and selection locations
        val selectionStart = textBox.selectionStart
        val selectionEnd = textBox.selectionEnd

        // #6762 values are reversed if using a keyboard and pressing Ctrl+Shift+LeftArrow
        val start = min(selectionStart, selectionEnd)
        val end = max(selectionStart, selectionEnd)
        val text = textBox.text?.toString() ?: ""

        // Split the text in the places where the formatting will take place
        val beforeText = text.substring(0, start)
        val selectedText = text.substring(start, end)
        val afterText = text.substring(end)
        val (newText, newStart, newEnd) = formatter.format(selectedText)

        // Update text field with updated text and selection
        val length = beforeText.length + newText.length + afterText.length
        val newFieldContent =
            StringBuilder(length).append(beforeText).append(newText).append(afterText)
        textBox.setText(newFieldContent)
        textBox.setSelection(start + newStart, start + newEnd)
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) {
            WidgetStatus.updateInBackground(this)
        }
    }

    @KotlinCleanup("convert KeyUtils to extension functions")
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (toolbar.onKeyUp(keyCode, event)) {
            // Toolbar was able to handle this key event. No need to handle it in NoteEditor too.
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ENTER -> if (event.isCtrlPressed) {
                // disable it in case of image occlusion
                if (allowSaveAndPreview()) {
                    launchCatchingTask { saveNote() }
                    return true
                }
            }
            KeyEvent.KEYCODE_D -> // null check in case Spinner is moved into options menu in the future
                if (event.isCtrlPressed) {
                    launchCatchingTask { deckSpinnerSelection!!.displayDeckSelectionDialog() }
                    return true
                }
            KeyEvent.KEYCODE_L -> if (event.isCtrlPressed) {
                showCardTemplateEditor()
                return true
            }
            KeyEvent.KEYCODE_N -> if (event.isCtrlPressed && noteTypeSpinner != null) {
                noteTypeSpinner!!.performClick()
                return true
            }
            KeyEvent.KEYCODE_T -> if (event.isCtrlPressed && event.isShiftPressed) {
                showTagsDialog()
                return true
            }
            KeyEvent.KEYCODE_C -> {
                if (event.isCtrlPressed && event.isShiftPressed) {
                    insertCloze(if (event.isAltPressed) AddClozeType.SAME_NUMBER else AddClozeType.INCREMENT_NUMBER)
                    // Anki Desktop warns, but still inserts the cloze
                    if (!isClozeType) {
                        showSnackbar(R.string.note_editor_insert_cloze_no_cloze_note_type)
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_P -> {
                if (event.isCtrlPressed) {
                    Timber.i("Ctrl+P: Preview Pressed")
                    if (allowSaveAndPreview()) {
                        launchCatchingTask { performPreview() }
                        return true
                    }
                }
            }
        }

        // 7573: Ctrl+Shift+[Num] to select a field
        if (event.isCtrlPressed && event.isShiftPressed) {
            val digit = KeyUtils.getDigit(event) ?: return super.onKeyUp(keyCode, event)
            // '0' is after '9' on the keyboard, so a user expects '10'
            val humanReadableDigit = if (digit == 0) 10 else digit
            // Subtract 1 to map to field index. '1' is the first field (index 0)
            selectFieldIndex(humanReadableDigit - 1)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun selectFieldIndex(index: Int) {
        Timber.i("Selecting field index %d", index)
        if (editFields!!.size <= index || index < 0) {
            Timber.i("Index out of range: %d", index)
            return
        }
        val field: FieldEditText? = try {
            editFields!![index]
        } catch (e: IndexOutOfBoundsException) {
            Timber.w(e, "Error selecting index %d", index)
            return
        }
        field!!.requestFocus()
        Timber.d("Selected field")
    }

    private fun insertCloze(addClozeType: AddClozeType) {
        val v = currentFocus as? FieldEditText ?: return
        convertSelectedTextToCloze(v, addClozeType)
    }

    private fun fetchIntentInformation(intent: Intent) {
        val extras = intent.extras ?: return
        sourceText = arrayOfNulls(2)
        if (Intent.ACTION_PROCESS_TEXT == intent.action) {
            val stringExtra = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
            Timber.d("Obtained %s from intent: %s", stringExtra, Intent.EXTRA_PROCESS_TEXT)
            sourceText!![0] = stringExtra ?: ""
            sourceText!![1] = ""
        } else if (ACTION_CREATE_FLASHCARD == intent.action) {
            // mSourceLanguage = extras.getString(SOURCE_LANGUAGE);
            // mTargetLanguage = extras.getString(TARGET_LANGUAGE);
            sourceText!![0] = extras.getString(SOURCE_TEXT)
            sourceText!![1] = extras.getString(TARGET_TEXT)
        } else {
            var first: String?
            var second: String?
            first = if (extras.getString(Intent.EXTRA_SUBJECT) != null) {
                extras.getString(Intent.EXTRA_SUBJECT)
            } else {
                ""
            }
            second = if (extras.getString(Intent.EXTRA_TEXT) != null) {
                extras.getString(Intent.EXTRA_TEXT)
            } else {
                ""
            }
            // Some users add cards via SEND intent from clipboard. In this case SUBJECT is empty
            if ("" == first) {
                // Assume that if only one field was sent then it should be the front
                first = second
                second = ""
            }
            val messages = Pair(first, second)
            sourceText!![0] = messages.first
            sourceText!![1] = messages.second
        }
    }

    private fun addFromAedict(extraText: String?): Boolean {
        var category: String
        val notepadLines = extraText!!.split("\n".toRegex()).toTypedArray()
        for (i in notepadLines.indices) {
            if (notepadLines[i].startsWith("[") && notepadLines[i].endsWith("]")) {
                category = notepadLines[i].substring(1, notepadLines[i].length - 1)
                if ("default" == category) {
                    if (notepadLines.size > i + 1) {
                        val entryLines = notepadLines[i + 1].split(":".toRegex()).toTypedArray()
                        if (entryLines.size > 1) {
                            sourceText!![0] = entryLines[1]
                            sourceText!![1] = entryLines[0]
                            aedictIntent = true
                            return false
                        }
                    }
                    showSnackbar(resources.getString(R.string.intent_aedict_empty))
                    return true
                }
            }
        }
        showSnackbar(resources.getString(R.string.intent_aedict_category))
        return true
    }

    @VisibleForTesting
    fun hasUnsavedChanges(): Boolean {
        if (!collectionHasLoaded()) {
            return false
        }

        // changed note type?
        if (!addNote && currentEditedCard != null) {
            val newModel: JSONObject? = currentlySelectedNotetype
            val oldModel: JSONObject = currentEditedCard!!.noteType(getColUnsafe)
            if (newModel != oldModel) {
                return true
            }
        }
        // changed deck?
        if (!addNote && currentEditedCard != null && currentEditedCard!!.currentDeckId().did != deckId) {
            return true
        }
        // changed fields?
        if (isFieldEdited) {
            for (value in editFields!!) {
                if (value?.text.toString() != "") {
                    return true
                }
            }
            return false
        } else {
            return isTagsEdited
        }
        // changed tags?
    }

    private fun collectionHasLoaded(): Boolean {
        return allModelIds != null
    }

    // ----------------------------------------------------------------------------
    // SAVE NOTE METHODS
    // ----------------------------------------------------------------------------

    @KotlinCleanup("return early and simplify if possible")
    private fun onNoteAdded() {
        var closeEditorAfterSave = false
        var closeIntent: Intent? = null
        changed = true
        sourceText = null
        refreshNoteData(FieldChangeType.refreshWithStickyFields(shouldReplaceNewlines()))
        showSnackbar(TR.addingAdded(), Snackbar.LENGTH_SHORT)

        if (caller == CALLER_NOTEEDITOR || aedictIntent) {
            closeEditorAfterSave = true
        } else if (caller == CALLER_NOTEEDITOR_INTENT_ADD) {
            closeEditorAfterSave = true
            closeIntent = Intent().apply { putExtra(EXTRA_ID, intent.getStringExtra(EXTRA_ID)) }
        } else if (!editFields!!.isEmpty()) {
            editFields!!.first()!!.focusWithKeyboard()
        }

        if (closeEditorAfterSave) {
            closeNoteEditor(closeIntent ?: Intent())
        } else {
            // Reset check for changes to fields
            isFieldEdited = false
            isTagsEdited = false
        }
    }

    private suspend fun saveNoteWithProgress() {
        // adding current note to collection
        withProgress(resources.getString(R.string.saving_facts)) {
            undoableOp {
                editorNote!!.notetype.put("tags", tags)
                notetypes.save(editorNote!!.notetype)
                addNote(editorNote!!, deckId)
            }
        }
        // update UI based on the result, noOfAddedCards
        onNoteAdded()
        updateFieldsFromStickyText()
    }

    @VisibleForTesting
    @NeedsTest("14664: 'first field must not be empty' no longer applies after saving the note")
    @KotlinCleanup("fix !! on oldModel/newModel")
    suspend fun saveNote() {
        val res = resources
        if (selectedTags == null) {
            selectedTags = ArrayList(0)
        }
        saveToggleStickyMap()

        // treat add new note and edit existing note independently
        if (addNote) {
            // load all of the fields into the note
            for (f in editFields!!) {
                updateField(f)
            }
            // Save deck to model
            Timber.d("setting 'last deck' of note type %s to %d", editorNote!!.notetype.name, deckId)
            editorNote!!.notetype.put("did", deckId)
            // Save tags to model
            editorNote!!.setTagsFromStr(getColUnsafe, tagsAsString(selectedTags!!))
            val tags = JSONArray()
            for (t in selectedTags!!) {
                tags.put(t)
            }

            reloadRequired = true

            lifecycleScope.launch {
                val noteFieldsCheck = checkNoteFieldsResponse(editorNote!!)
                if (noteFieldsCheck is NoteFieldsCheckResult.Failure) {
                    addNoteErrorMessage = noteFieldsCheck.getLocalizedMessage(this@NoteEditor)
                    displayErrorSavingNote()
                    return@launch
                }
                addNoteErrorMessage = null
                saveNoteWithProgress()
            }
        } else {
            // Check whether note type has been changed
            val newModel = currentlySelectedNotetype
            val oldModel = currentEditedCard?.noteType(getColUnsafe)
            if (newModel?.id != oldModel?.id) {
                reloadRequired = true
                if (modelChangeCardMap!!.size < editorNote!!.numberOfCards(getColUnsafe) || modelChangeCardMap!!.containsValue(
                        null
                    )
                ) {
                    // If cards will be lost via the new mapping then show a confirmation dialog before proceeding with the change
                    val dialog = ConfirmationDialog()
                    dialog.setArgs(res.getString(R.string.confirm_map_cards_to_nothing))
                    val confirm = Runnable {
                        // Bypass the check once the user confirms
                        changeNoteType(oldModel!!, newModel!!)
                    }
                    dialog.setConfirm(confirm)
                    showDialogFragment(dialog)
                } else {
                    // Otherwise go straight to changing note type
                    changeNoteType(oldModel!!, newModel!!)
                }
                return
            }
            // Regular changes in note content
            var modified = false
            // changed did? this has to be done first as remFromDyn() involves a direct write to the database
            if (currentEditedCard != null && currentEditedCard!!.currentDeckId().did != deckId) {
                reloadRequired = true
                undoableOp { setDeck(listOf(currentEditedCard!!.id), deckId) }
                // refresh the card object to reflect the database changes from above
                currentEditedCard!!.load(getColUnsafe)
                // also reload the note object
                editorNote = currentEditedCard!!.note(getColUnsafe)
                // then set the card ID to the new deck
                currentEditedCard!!.did = deckId
                modified = true
                Timber.d("deck ID updated to '%d'", deckId)
            }
            // now load any changes to the fields from the form
            for (f in editFields!!) {
                modified = modified or updateField(f)
            }
            // added tag?
            for (t in selectedTags!!) {
                modified = modified || !editorNote!!.hasTag(getColUnsafe, tag = t)
            }
            // removed tag?
            modified = modified || editorNote!!.tags.size > selectedTags!!.size

            if (!modified) {
                closeNoteEditor()
                return
            }

            editorNote!!.setTagsFromStr(getColUnsafe, tagsAsString(selectedTags!!))
            changed = true

            // these activities are updated to handle `opChanges`
            // and no longer using the legacy ActivityResultCallback/onActivityResult to
            // accept & update the note in the activity
            if (caller == CALLER_PREVIEWER_EDIT || caller == CALLER_EDIT) {
                withProgress {
                    undoableOp {
                        updateNote(currentEditedCard!!.note())
                    }
                }
            }
            closeNoteEditor()
            return
        }
    }

    /**
     * Change the note type from oldModel to newModel, handling the case where a full sync will be required
     */
    @NeedsTest("test changing note type")
    private fun changeNoteType(oldNotetype: NotetypeJson, newNotetype: NotetypeJson) = launchCatchingTask {
        if (!userAcceptsSchemaChange()) return@launchCatchingTask

        val noteId = editorNote!!.id
        undoableOp {
            notetypes.change(oldNotetype, noteId, newNotetype, modelChangeFieldMap!!, modelChangeCardMap!!)
        }
        // refresh the note object to reflect the database changes
        withCol { editorNote!!.load() }
        // close note editor
        closeNoteEditor()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (unmountReceiver != null) {
            unregisterReceiver(unmountReceiver)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateToolbar()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.note_editor, menu)
        if (addNote) {
            menu.findItem(R.id.action_copy_note).isVisible = false
            val iconVisible = allowSaveAndPreview()
            menu.findItem(R.id.action_save).isVisible = iconVisible
            menu.findItem(R.id.action_preview).isVisible = iconVisible
        } else {
            menu.findItem(R.id.action_add_note_from_note_editor).isVisible = true
        }
        if (editFields != null) {
            for (i in editFields!!.indices) {
                val fieldText = editFields!![i]!!.text
                if (!fieldText.isNullOrEmpty()) {
                    menu.findItem(R.id.action_copy_note).isEnabled = true
                    break
                } else if (i == editFields!!.size - 1) {
                    menu.findItem(R.id.action_copy_note).isEnabled = false
                }
            }
        }
        menu.findItem(R.id.action_show_toolbar).isChecked =
            !shouldHideToolbar()
        menu.findItem(R.id.action_capitalize).isChecked =
            this.sharedPrefs().getBoolean(PREF_NOTE_EDITOR_CAPITALIZE, true)
        menu.findItem(R.id.action_scroll_toolbar).isChecked =
            this.sharedPrefs().getBoolean(PREF_NOTE_EDITOR_SCROLL_TOOLBAR, true)
        return super.onCreateOptionsMenu(menu)
    }

    /**
     * When using the options such as image occlusion we don't need the menu's save/preview
     * option to save/preview the card as it has a built in option and the user is notified
     * when the card is saved successfully
     */
    private fun allowSaveAndPreview(): Boolean = when {
        addNote && currentNotetypeIsImageOcclusion() -> false
        else -> true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                Timber.i("NoteEditor:: Home button pressed")
                closeCardEditorWithCheck()
                return true
            }
            R.id.action_preview -> {
                Timber.i("NoteEditor:: Preview button pressed")
                if (allowSaveAndPreview()) {
                    launchCatchingTask { performPreview() }
                }
                return true
            }
            R.id.action_save -> {
                Timber.i("NoteEditor:: Save note button pressed")
                if (allowSaveAndPreview()) {
                    launchCatchingTask { saveNote() }
                }
                return true
            }
            R.id.action_add_note_from_note_editor -> {
                Timber.i("NoteEditor:: Add Note button pressed")
                addNewNote()
                return true
            }
            R.id.action_copy_note -> {
                Timber.i("NoteEditor:: Copy Note button pressed")
                copyNote()
                return true
            }
            R.id.action_font_size -> {
                Timber.i("NoteEditor:: Font Size button pressed")
                val repositionDialog = IntegerDialog()
                repositionDialog.setArgs(getString(R.string.menu_font_size), editTextFontSize, 2)
                repositionDialog.setCallbackRunnable { fontSizeSp: Int? -> setFontSize(fontSizeSp) }
                showDialogFragment(repositionDialog)
                return true
            }
            R.id.action_show_toolbar -> {
                item.isChecked = !item.isChecked
                this.sharedPrefs().edit {
                    putBoolean(PREF_NOTE_EDITOR_SHOW_TOOLBAR, item.isChecked)
                }
                updateToolbar()
            }
            R.id.action_capitalize -> {
                Timber.i("NoteEditor:: Capitalize button pressed. New State: %b", !item.isChecked)
                item.isChecked = !item.isChecked // Needed for Android 9
                toggleCapitalize(item.isChecked)
                return true
            }
            R.id.action_scroll_toolbar -> {
                item.isChecked = !item.isChecked
                this.sharedPrefs().edit {
                    putBoolean(PREF_NOTE_EDITOR_SCROLL_TOOLBAR, item.isChecked)
                }
                updateToolbar()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toggleCapitalize(value: Boolean) {
        this.sharedPrefs().edit {
            putBoolean(PREF_NOTE_EDITOR_CAPITALIZE, value)
        }
        for (f in editFields!!) {
            f!!.setCapitalize(value)
        }
    }

    private fun setFontSize(fontSizeSp: Int?) {
        if (fontSizeSp == null || fontSizeSp <= 0) {
            return
        }
        Timber.i("Setting font size to %d", fontSizeSp)
        this.sharedPrefs().edit { putInt(PREF_NOTE_EDITOR_FONT_SIZE, fontSizeSp) }
        for (f in editFields!!) {
            f!!.textSize = fontSizeSp.toFloat()
        }
    }

    // Note: We're not being accurate here - the initial value isn't actually what's supplied in the layout.xml
    // So a value of 18sp in the XML won't be 18sp on the TextView, but it's close enough.
    // Values are setFontSize are whole when returned.
    private val editTextFontSize: String
        get() {
            // Note: We're not being accurate here - the initial value isn't actually what's supplied in the layout.xml
            // So a value of 18sp in the XML won't be 18sp on the TextView, but it's close enough.
            // Values are setFontSize are whole when returned.
            val sp = TextViewUtil.getTextSizeSp(editFields!!.first()!!)
            return sp.roundToInt().toString()
        }

    private fun addNewNote() {
        openNewNoteEditor { }
    }

    fun copyNote() {
        openNewNoteEditor { intent: Intent ->
            intent.putExtra(EXTRA_CONTENTS, fieldsText)
            if (selectedTags != null) {
                intent.putExtra(EXTRA_TAGS, selectedTags!!.toTypedArray())
            }
        }
    }

    private fun openNewNoteEditor(intentEnricher: Consumer<Intent>) {
        val intent = Intent(this@NoteEditor, NoteEditor::class.java)
        intent.putExtra(EXTRA_CALLER, CALLER_NOTEEDITOR)
        intent.putExtra(EXTRA_DID, deckId)
        // mutate event with additional properties
        intentEnricher.accept(intent)
        requestAddLauncher.launch(intent)
    }

    // ----------------------------------------------------------------------------
    // CUSTOM METHODS
    // ----------------------------------------------------------------------------
    @VisibleForTesting
    @NeedsTest("previewing newlines")
    @NeedsTest("cards with a cloze notetype but no cloze in fields are previewed as empty card")
    @NeedsTest("clozes that don't start at '1' are correctly displayed")
    suspend fun performPreview() {
        val convertNewlines = shouldReplaceNewlines()
        fun String?.toFieldText(): String = NoteService.convertToHtmlNewline(this.toString(), convertNewlines)
        val fields = editFields?.mapTo(mutableListOf()) { it!!.fieldText.toFieldText() } ?: mutableListOf()
        val tags = selectedTags ?: mutableListOf()

        val ord = if (editorNote!!.notetype.isCloze) {
            val tempNote = withCol { Note.fromNotetypeId(editorNote!!.notetype.id) }
            tempNote.fields = fields // makes possible to get the cloze numbers from the fields
            val clozeNumbers = withCol { clozeNumbersInNote(tempNote) }
            if (clozeNumbers.isNotEmpty()) {
                clozeNumbers.first() - 1
            } else {
                0
            }
        } else {
            currentEditedCard?.ord ?: 0
        }

        val args = TemplatePreviewerArguments(
            notetypeFile = NotetypeFile(this, editorNote!!.notetype),
            fields = fields,
            tags = tags,
            id = editorNote!!.id,
            ord = ord,
            fillEmpty = false
        )
        val intent = TemplatePreviewerFragment.getIntent(this, args)
        startActivity(intent)
    }

    /**
     * finish when sd card is ejected
     */
    private fun registerExternalStorageListener() {
        if (unmountReceiver == null) {
            unmountReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != null && intent.action == SdCardReceiver.MEDIA_EJECT) {
                        finish()
                    }
                }
            }
            val iFilter = IntentFilter()
            iFilter.addAction(SdCardReceiver.MEDIA_EJECT)
            registerReceiverCompat(unmountReceiver, iFilter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    private fun setTags(tags: Array<String>) {
        selectedTags = tags.toCollection(ArrayList())
        updateTags()
    }

    private fun closeCardEditorWithCheck() {
        if (hasUnsavedChanges()) {
            showDiscardChangesDialog()
        } else {
            closeNoteEditor()
        }
    }

    private fun showDiscardChangesDialog() {
        DiscardChangesDialog.showDialog(this) {
            Timber.i("NoteEditor:: OK button pressed to confirm discard changes")
            closeNoteEditor()
        }
    }

    private fun closeNoteEditor(intent: Intent = Intent()) {
        val result: Int = if (changed) {
            RESULT_OK
        } else {
            RESULT_CANCELED
        }
        if (reloadRequired) {
            intent.putExtra(RELOAD_REQUIRED_EXTRA_KEY, true)
        }
        if (changed) {
            intent.putExtra(NOTE_CHANGED_EXTRA_KEY, true)
        }
        closeNoteEditor(result, intent)
    }

    private fun closeNoteEditor(result: Int, intent: Intent?) {
        if (intent != null) {
            setResult(result, intent)
        } else {
            setResult(result)
        }
        // ensure there are no orphans from possible edit previews
        CardTemplateNotetype.clearTempModelFiles()

        // Set the finish animation if there is one on the intent which created the activity
        val animation = IntentCompat.getParcelableExtra(
            this.intent,
            FINISH_ANIMATION_EXTRA,
            ActivityTransitionAnimation.Direction::class.java
        )
        if (animation != null) {
            finishWithAnimation(animation)
        } else {
            finish()
        }
    }

    private fun showTagsDialog() {
        if (selectedTags == null) {
            selectedTags = ArrayList(0)
        }
        val tags = ArrayList(getColUnsafe.tags.all())
        val selTags = ArrayList(selectedTags!!)
        val dialog = tagsDialogFactory!!.newTagsDialog()
            .withArguments(TagsDialog.DialogType.EDIT_TAGS, selTags, tags)
        showDialogFragment(dialog)
    }

    override fun onSelectedTags(
        selectedTags: List<String>,
        indeterminateTags: List<String>,
        stateFilter: CardStateFilter
    ) {
        if (this.selectedTags != selectedTags) {
            isTagsEdited = true
        }
        this.selectedTags = selectedTags as ArrayList<String>?
        updateTags()
    }

    private fun showCardTemplateEditor() {
        val intent = Intent(this, CardTemplateEditor::class.java)
        // Pass the model ID
        intent.putExtra("modelId", currentlySelectedNotetype!!.id)
        Timber.d(
            "showCardTemplateEditor() for model %s",
            intent.getLongExtra("modelId", NOT_FOUND_NOTE_TYPE)
        )
        // Also pass the note id and ord if not adding new note
        if (!addNote && currentEditedCard != null) {
            intent.putExtra("noteId", currentEditedCard!!.nid)
            Timber.d("showCardTemplateEditor() with note %s", currentEditedCard!!.nid)
            intent.putExtra("ordId", currentEditedCard!!.ord)
            Timber.d("showCardTemplateEditor() with ord %s", currentEditedCard!!.ord)
        }
        requestTemplateEditLauncher.launch(intent)
    }

    /** Appends a string at the selection point, or appends to the end if not in focus  */
    @VisibleForTesting
    fun insertStringInField(fieldEditText: EditText?, formattedValue: String?) {
        if (fieldEditText!!.hasFocus()) {
            // Crashes if start > end, although this is fine for a selection via keyboard.
            val start = fieldEditText.selectionStart
            val end = fieldEditText.selectionEnd
            fieldEditText.text.replace(min(start, end), max(start, end), formattedValue)
        } else {
            fieldEditText.text.append(formattedValue)
        }
    }

    /** Sets EditText at index [fieldIndex]'s text to [newString] */
    @VisibleForTesting
    fun setField(fieldIndex: Int, newString: String) {
        clearField(fieldIndex)
        insertStringInField(getFieldForTest(fieldIndex), newString)
    }

    /** @param col Readonly variable to get cache dir
     */
    @KotlinCleanup("fix the requireNoNulls")
    private fun getCurrentMultimediaEditableNote(col: Collection): MultimediaEditableNote {
        val note = NoteService.createEmptyNote(editorNote!!.notetype)
        val fields = currentFieldStrings.requireNoNulls()
        NoteService.updateMultimediaNoteFromFields(col, fields, editorNote!!.mid, note!!)
        return note
    }

    val currentFields: JSONArray
        get() = editorNote!!.notetype.getJSONArray("flds")

    @get:CheckResult
    val currentFieldStrings: Array<String?>
        get() {
            if (editFields == null) {
                return arrayOfNulls(0)
            }
            val ret = arrayOfNulls<String>(editFields!!.size)
            for (i in editFields!!.indices) {
                ret[i] = getCurrentFieldText(i)
            }
            return ret
        }

    private fun populateEditFields(type: FieldChangeType, editModelMode: Boolean) {
        val editLines = fieldState.loadFieldEditLines(type)
        fieldsLayoutContainer!!.removeAllViews()
        customViewIds.clear()
        imageOcclusionButtonsContainer?.isVisible = currentNotetypeIsImageOcclusion()

        val indicesToHide = mutableListOf<Int>()
        if (currentNotetypeIsImageOcclusion()) {
            val occlusionTag = "0"
            val imageTag = "1"
            val fields = currentlySelectedNotetype!!.getJSONArray("flds")
            for (i in 0 until fields.length()) {
                val tag = fields.getJSONObject(i).getString("tag")
                if (tag == occlusionTag || tag == imageTag) {
                    indicesToHide.add(i)
                }
            }
        }

        editFields = LinkedList()

        var previous: FieldEditLine? = null
        customViewIds.ensureCapacity(editLines.size)
        for (i in editLines.indices) {
            val editLineView = editLines[i]
            customViewIds.add(editLineView.id)
            val newEditText = editLineView.editText
            newEditText.setImagePasteListener { editText: EditText?, uri: Uri? ->
                onImagePaste(
                    editText!!,
                    uri!!
                )
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (i == 0) {
                    findViewById<View>(R.id.note_deck_spinner).nextFocusForwardId = newEditText.id
                }
                if (previous != null) {
                    previous.lastViewInTabOrder.nextFocusForwardId = newEditText.id
                }
            }
            previous = editLineView
            editLineView.setEnableAnimation(animationEnabled())

            // Use custom implementation of ActionMode.Callback customize selection and insert menus
            editLineView.setActionModeCallbacks(getActionModeCallback(newEditText, View.generateViewId()))
            editLineView.setHintLocale(getHintLocaleForField(editLineView.name))
            initFieldEditText(newEditText, i, !editModelMode)
            editFields!!.add(newEditText)
            val prefs = this.sharedPrefs()
            if (prefs.getInt(PREF_NOTE_EDITOR_FONT_SIZE, -1) > 0) {
                newEditText.textSize = prefs.getInt(PREF_NOTE_EDITOR_FONT_SIZE, -1).toFloat()
            }
            newEditText.setCapitalize(prefs.getBoolean(PREF_NOTE_EDITOR_CAPITALIZE, true))
            val mediaButton = editLineView.mediaButton
            val toggleStickyButton = editLineView.toggleSticky
            // Make the icon change between media icon and switch field icon depending on whether editing note type
            if (editModelMode && allowFieldRemapping()) {
                // Allow remapping if originally more than two fields
                mediaButton.setBackgroundResource(R.drawable.ic_import_export)
                setRemapButtonListener(mediaButton, i)
                toggleStickyButton.setBackgroundResource(0)
            } else if (editModelMode && !allowFieldRemapping()) {
                mediaButton.setBackgroundResource(0)
                toggleStickyButton.setBackgroundResource(0)
            } else {
                // Use media editor button if not changing note type
                mediaButton.setBackgroundResource(R.drawable.ic_attachment)
                setMMButtonListener(mediaButton, i)
                if (addNote) {
                    // toggle sticky button
                    toggleStickyButton.setBackgroundResource(R.drawable.ic_baseline_push_pin_24)
                    setToggleStickyButtonListener(toggleStickyButton, i)
                } else {
                    toggleStickyButton.setBackgroundResource(0)
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                previous.lastViewInTabOrder.nextFocusForwardId = R.id.CardEditorTagButton
            }
            mediaButton.contentDescription =
                getString(R.string.multimedia_editor_attach_mm_content, editLineView.name)
            toggleStickyButton.contentDescription =
                getString(R.string.note_editor_toggle_sticky, editLineView.name)

            editLineView.isVisible = i !in indicesToHide
            fieldsLayoutContainer!!.addView(editLineView)
        }
    }

    private fun getActionModeCallback(textBox: FieldEditText, clozeMenuId: Int): ActionMode.Callback {
        return CustomActionModeCallback(
            isClozeType,
            getString(R.string.multimedia_editor_popup_cloze),
            clozeMenuId,
            onActionItemSelected = { mode, item ->
                if (item.itemId == clozeMenuId) {
                    convertSelectedTextToCloze(textBox, AddClozeType.INCREMENT_NUMBER)
                    mode.finish()
                    true
                } else {
                    false
                }
            }
        )
    }

    private fun onImagePaste(editText: EditText, uri: Uri): Boolean {
        val imageTag = mediaRegistration!!.onImagePaste(uri) ?: return false
        insertStringInField(editText, imageTag)
        return true
    }

    private fun setMMButtonListener(mediaButton: ImageButton, index: Int) {
        mediaButton.setOnClickListener { v: View ->
            Timber.i("NoteEditor:: Multimedia button pressed for field %d", index)
            if (editorNote!!.items()[index][1].isNotEmpty()) {
                // If the field already exists then we start the field editor, which figures out the type
                // automatically
                val note: IMultimediaEditableNote = getCurrentMultimediaEditableNote(getColUnsafe)
                startMultimediaFieldEditor(index, note)
            } else {
                // Otherwise we make a popup menu allowing the user to choose between audio/image/text field
                val popup = PopupMenu(this@NoteEditor, v)
                val inflater = popup.menuInflater
                inflater.inflate(R.menu.popupmenu_multimedia_options, popup.menu)

                (popup.menu as? MenuBuilder)?.let { menu ->
                    menu.setOptionalIconsVisible(true)
                    increaseHorizontalPaddingOfOverflowMenuIcons(menu)
                    tintOverflowMenuIcons(menu)
                }

                popup.setOnMenuItemClickListener { item: MenuItem ->
                    when (item.itemId) {
                        R.id.menu_multimedia_audio -> {
                            Timber.i("NoteEditor:: Record audio button pressed")
                            startMultimediaFieldEditorForField(index, AudioRecordingField())
                            return@setOnMenuItemClickListener true
                        }
                        R.id.menu_multimedia_audio_clip, R.id.menu_multimedia_video_clip -> {
                            Timber.i("NoteEditor:: Add audio clip button pressed")
                            startMultimediaFieldEditorForField(index, MediaClipField())
                            return@setOnMenuItemClickListener true
                        }
                        R.id.menu_multimedia_photo -> {
                            Timber.i("NoteEditor:: Add image button pressed")
                            startMultimediaFieldEditorForField(index, ImageField())
                            return@setOnMenuItemClickListener true
                        }
                        R.id.menu_multimedia_text -> {
                            Timber.i("NoteEditor:: Advanced editor button pressed")
                            startAdvancedTextEditor(index)
                            return@setOnMenuItemClickListener true
                        }
                    }
                    false
                }
                if (AdaptionUtil.isXiaomiRestrictedLearningDevice) {
                    popup.menu.findItem(R.id.menu_multimedia_photo).isVisible = false
                    popup.menu.findItem(R.id.menu_multimedia_text).isVisible = false
                }
                popup.show()
            }
        }
    }

    @NeedsTest("If a field is sticky after synchronization, the toggleStickyButton should be activated.")
    private fun setToggleStickyButtonListener(toggleStickyButton: ImageButton?, index: Int) {
        if (currentFields.getJSONObject(index).getBoolean("sticky")) {
            toggleStickyText.getOrPut(index) { "" }
        }
        if (toggleStickyText[index] == null) {
            toggleStickyButton!!.background.alpha = 64
        } else {
            toggleStickyButton!!.background.alpha = 255
        }
        toggleStickyButton.setOnClickListener {
            onToggleStickyText(
                toggleStickyButton,
                index
            )
        }
    }

    private fun onToggleStickyText(toggleStickyButton: ImageButton?, index: Int) {
        val text = editFields!![index]!!.fieldText
        if (toggleStickyText[index] == null) {
            toggleStickyText[index] = text
            toggleStickyButton!!.background.alpha = 255
            Timber.d("Saved Text:: %s", toggleStickyText[index])
        } else {
            toggleStickyText.remove(index)
            toggleStickyButton!!.background.alpha = 64
        }
    }

    @NeedsTest("13719: moving from a note type with more fields to one with fewer fields")
    private fun saveToggleStickyMap() {
        for ((key) in toggleStickyText.toMap()) {
            // handle fields for different note type with different size
            if (key < editFields!!.size) {
                toggleStickyText[key] = editFields!![key]?.fieldText
            } else {
                toggleStickyText.remove(key)
            }
        }
    }

    private fun updateFieldsFromStickyText() {
        for ((key, value) in toggleStickyText) {
            // handle fields for different note type with different size
            if (key < editFields!!.size) {
                editFields!![key]!!.setText(value)
            }
        }
    }

    @VisibleForTesting
    fun clearField(index: Int) {
        setFieldValueFromUi(index, "")
    }

    private fun startMultimediaFieldEditorForField(index: Int, field: IField) {
        val note: IMultimediaEditableNote = getCurrentMultimediaEditableNote(getColUnsafe)
        note.setField(index, field)
        startMultimediaFieldEditor(index, note)
    }

    private fun setRemapButtonListener(remapButton: ImageButton?, newFieldIndex: Int) {
        remapButton!!.setOnClickListener { v: View? ->
            Timber.i("NoteEditor:: Remap button pressed for new field %d", newFieldIndex)
            // Show list of fields from the original note which we can map to
            val popup = PopupMenu(this@NoteEditor, v!!)
            val items = editorNote!!.items()
            for (i in items.indices) {
                popup.menu.add(Menu.NONE, i, Menu.NONE, items[i][0])
            }
            // Add "nothing" at the end of the list
            popup.menu.add(Menu.NONE, items.size, Menu.NONE, R.string.nothing)
            popup.setOnMenuItemClickListener { item: MenuItem ->
                // Get menu item id
                val idx = item.itemId
                Timber.i("NoteEditor:: User chose to remap to old field %d", idx)
                // Retrieve any existing mappings between newFieldIndex and idx
                val previousMapping = MapUtil.getKeyByValue(modelChangeFieldMap!!, newFieldIndex)
                val mappingConflict = modelChangeFieldMap!![idx]
                // Update the mapping depending on any conflicts
                if (idx == items.size && previousMapping != null) {
                    // Remove the previous mapping if None selected
                    modelChangeFieldMap!!.remove(previousMapping)
                } else if (idx < items.size && mappingConflict != null && previousMapping != null && newFieldIndex != mappingConflict) {
                    // Swap the two mappings if there was a conflict and previous mapping
                    modelChangeFieldMap!![previousMapping] = mappingConflict
                    modelChangeFieldMap!![idx] = newFieldIndex
                } else if (idx < items.size && mappingConflict != null) {
                    // Set the conflicting field to None if no previous mapping to swap into it
                    modelChangeFieldMap!!.remove(previousMapping)
                    modelChangeFieldMap!![idx] = newFieldIndex
                } else if (idx < items.size) {
                    // Can simply set the new mapping if no conflicts
                    modelChangeFieldMap!![idx] = newFieldIndex
                }
                // Reload the fields
                updateFieldsFromMap(currentlySelectedNotetype)
                true
            }
            popup.show()
        }
    }

    private fun startMultimediaFieldEditor(index: Int, note: IMultimediaEditableNote?, imageUri: Uri? = null) {
        val field = note!!.getField(index)!!
        val editCard = Intent(this@NoteEditor, MultimediaEditFieldActivity::class.java)
        editCard.putExtra(MultimediaEditFieldActivity.INTENT_IMAGE_URI, imageUri)
        editCard.putExtra(MultimediaEditFieldActivity.EXTRA_MULTIMEDIA_EDIT_FIELD_ACTIVITY, MultimediaEditFieldActivityExtra(index, field, note))
        requestMultiMediaEditLauncher.launch(editCard)
    }

    private fun initFieldEditText(editText: FieldEditText?, index: Int, enabled: Boolean) {
        // Listen for changes in the first field so we can re-check duplicate status.
        editText!!.addTextChangedListener(EditFieldTextWatcher(index))
        if (index == 0) {
            editText.onFocusChangeListener = OnFocusChangeListener { _: View?, hasFocus: Boolean ->
                try {
                    if (hasFocus) {
                        // we only want to decorate when we lose focus
                        return@OnFocusChangeListener
                    }
                    @SuppressLint("CheckResult")
                    val currentFieldStrings = currentFieldStrings
                    if (currentFieldStrings.size != 2 || currentFieldStrings[1]!!.isNotEmpty()) {
                        // we only decorate on 2-field cards while second field is still empty
                        return@OnFocusChangeListener
                    }
                    val firstField = currentFieldStrings[0]
                    val decoratedText = NoteFieldDecorator.aplicaHuevo(firstField)
                    if (decoratedText != firstField) {
                        // we only apply the decoration if it is actually different from the first field
                        setFieldValueFromUi(1, decoratedText)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Unable to decorate text field")
                }
            }
        }

        // Sets the background color of disabled EditText.
        if (!enabled) {
            editText.setBackgroundColor(
                MaterialColors.getColor(
                    this@NoteEditor,
                    R.attr.editTextBackgroundColor,
                    0
                )
            )
        }
        editText.isEnabled = enabled
    }

    @KotlinCleanup("make name non-null in FieldEditLine")
    private fun getHintLocaleForField(name: String?): Locale? {
        val field = getFieldByName(name) ?: return null
        return LanguageHintService.getLanguageHintForField(field)
    }

    private fun getFieldByName(name: String?): JSONObject? {
        val pair: Pair<Int, JSONObject>? = try {
            Notetypes.fieldMap(currentlySelectedNotetype!!)[name]
        } catch (e: Exception) {
            Timber.w("Failed to obtain field '%s'", name)
            return null
        }
        return pair?.second
    }

    private fun setEditFieldTexts(contents: String?) {
        var fields: List<String>? = null
        val len: Int
        if (contents == null) {
            len = 0
        } else {
            fields = Utils.splitFields(contents)
            len = fields.size
        }
        for (i in editFields!!.indices) {
            if (i < len) {
                editFields!![i]!!.setText(fields!![i])
            } else {
                editFields!![i]!!.setText("")
            }
        }
    }

    private fun setDuplicateFieldStyles() {
        // #15579 can be null if switching between two image occlusion types
        if (editFields == null) return
        val field = editFields!![0]
        // Keep copy of current internal value for this field.
        val oldValue = editorNote!!.fields[0]
        // Update the field in the Note so we can run a dupe check on it.
        updateField(field)
        // 1 is empty, 2 is dupe, null is neither.
        val dupeCode = editorNote!!.fieldsCheck(getColUnsafe)
        // Change bottom line color of text field
        if (dupeCode == NoteFieldsCheckResponse.State.DUPLICATE) {
            field!!.setDupeStyle()
        } else {
            field!!.setDefaultStyle()
        }
        // Put back the old value so we don't interfere with modification detection
        editorNote!!.values()[0] = oldValue
    }

    @KotlinCleanup("remove 'requireNoNulls'")
    private val fieldsText: String
        get() {
            val fields = arrayOfNulls<String>(editFields!!.size)
            for (i in editFields!!.indices) {
                fields[i] = getCurrentFieldText(i)
            }
            return Utils.joinFields(fields.requireNoNulls())
        }

    /** Returns the value of the field at the given index  */
    private fun getCurrentFieldText(index: Int): String {
        val fieldText = editFields!![index]!!.text ?: return ""
        return fieldText.toString()
    }

    private fun setDid(note: Note?) {
        fun calculateDeckId(): DeckId {
            if (deckId != 0L) return deckId
            if (note != null && !addNote && currentEditedCard != null) {
                return currentEditedCard!!.currentDeckId().did
            }

            if (!getColUnsafe.config.getBool(ConfigKey.Bool.ADDING_DEFAULTS_TO_CURRENT_DECK)) {
                return getColUnsafe.notetypes.current().let {
                    Timber.d("Adding to deck of note type, noteType: %s", it.name)
                    return@let it.did
                }
            }

            val currentDeckId = getColUnsafe.config.get(CURRENT_DECK) ?: 1L
            return if (getColUnsafe.decks.isFiltered(currentDeckId)) {
                /*
                 * If the deck in mCurrentDid is a filtered (dynamic) deck, then we can't create cards in it,
                 * and we set mCurrentDid to the Default deck. Otherwise, we keep the number that had been
                 * selected previously in the activity.
                 */
                1
            } else {
                currentDeckId
            }
        }

        deckId = calculateDeckId()
        launchCatchingTask { deckSpinnerSelection!!.selectDeckById(deckId, false) }
    }

    /** Refreshes the UI using the currently selected model as a template  */
    private fun refreshNoteData(changeType: FieldChangeType) {
        setNote(null, changeType)
    }

    /** Handles setting the current note (non-null afterwards) and rebuilding the UI based on this note  */
    private fun setNote(note: Note?, changeType: FieldChangeType) {
        editorNote = if (note == null || addNote) {
            getColUnsafe.run {
                val notetype = notetypes.current()
                Note.fromNotetypeId(notetype.id)
            }
        } else {
            note
        }
        if (selectedTags == null) {
            selectedTags = editorNote!!.tags
        }
        // nb: setOnItemSelectedListener and populateEditFields need to occur after this
        setNoteTypePosition()
        setDid(note)
        updateTags()
        updateCards(editorNote!!.notetype)
        updateToolbar()
        populateEditFields(changeType, false)
        updateFieldsFromStickyText()
    }

    private fun addClozeButton(@DrawableRes drawableRes: Int, description: String, type: AddClozeType) {
        val drawable = ResourcesCompat.getDrawable(resources, drawableRes, null)!!.apply {
            setTint(MaterialColors.getColor(this@NoteEditor, R.attr.toolbarIconColor, 0))
        }
        val button = toolbar.insertItem(0, drawable) { insertCloze(type) }.apply {
            contentDescription = description
        }
        TooltipCompat.setTooltipText(button, description)
    }

    private fun updateToolbar() {
        val editorLayout = findViewById<View>(R.id.note_editor_layout)
        val bottomMargin =
            if (shouldHideToolbar()) {
                0
            } else {
                resources.getDimension(R.dimen.note_editor_toolbar_height)
                    .toInt()
            }
        val params = editorLayout.layoutParams as MarginLayoutParams
        params.bottomMargin = bottomMargin
        editorLayout.layoutParams = params
        if (shouldHideToolbar()) {
            toolbar.visibility = View.GONE
            return
        } else {
            toolbar.visibility = View.VISIBLE
        }
        toolbar.clearCustomItems()
        if (editorNote!!.notetype.isCloze) {
            addClozeButton(
                drawableRes = R.drawable.ic_cloze_new_card,
                description = TR.editingClozeDeletion(),
                type = AddClozeType.INCREMENT_NUMBER
            )
            addClozeButton(
                drawableRes = R.drawable.ic_cloze_same_card,
                description = TR.editingClozeDeletionRepeat(),
                type = AddClozeType.SAME_NUMBER
            )
        }
        val buttons = toolbarButtons
        for (b in buttons) {
            // 0th button shows as '1' and is Ctrl + 1
            val visualIndex = b.index + 1
            var text = visualIndex.toString()
            if (b.buttonText.isNotEmpty()) {
                text = b.buttonText
            }
            val bmp = toolbar.createDrawableForString(text)
            val v = toolbar.insertItem(0, bmp, b.toFormatter())
            v.contentDescription = text

            // Allow Ctrl + 1...Ctrl + 0 for item 10.
            v.tag = (visualIndex % 10).toString()
            v.setOnLongClickListener {
                displayEditToolbarDialog(b)
                true
            }
        }

        // Let the user add more buttons (always at the end).
        // Sets the add custom tag icon color.
        val drawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_add_toolbar_icon, null)
        drawable!!.setTint(MaterialColors.getColor(this@NoteEditor, R.attr.toolbarIconColor, 0))
        val addButton = toolbar.insertItem(0, drawable) { displayAddToolbarDialog() }
        addButton.contentDescription = resources.getString(R.string.add_toolbar_item)
        TooltipCompat.setTooltipText(addButton, resources.getString(R.string.add_toolbar_item))
    }

    private val toolbarButtons: ArrayList<CustomToolbarButton>
        get() {
            val set = this.sharedPrefs()
                .getStringSet(PREF_NOTE_EDITOR_CUSTOM_BUTTONS, HashUtil.hashSetInit(0))
            return CustomToolbarButton.fromStringSet(set!!)
        }

    private fun saveToolbarButtons(buttons: ArrayList<CustomToolbarButton>) {
        this.sharedPrefs().edit {
            putStringSet(PREF_NOTE_EDITOR_CUSTOM_BUTTONS, CustomToolbarButton.toStringSet(buttons))
        }
    }

    private fun addToolbarButton(buttonText: String, prefix: String, suffix: String) {
        if (prefix.isEmpty() && suffix.isEmpty()) return
        val toolbarButtons = toolbarButtons
        toolbarButtons.add(CustomToolbarButton(toolbarButtons.size, buttonText, prefix, suffix))
        saveToolbarButtons(toolbarButtons)
        updateToolbar()
    }

    private fun editToolbarButton(
        buttonText: String,
        prefix: String,
        suffix: String,
        currentButton: CustomToolbarButton
    ) {
        val toolbarButtons = toolbarButtons
        val currentButtonIndex = currentButton.index

        toolbarButtons[currentButtonIndex] = CustomToolbarButton(
            index = currentButtonIndex,
            buttonText = buttonText.ifEmpty { currentButton.buttonText },
            prefix = prefix.ifEmpty { currentButton.prefix },
            suffix = suffix.ifEmpty { currentButton.suffix }
        )

        saveToolbarButtons(toolbarButtons)
        updateToolbar()
    }

    private fun suggestRemoveButton(
        button: CustomToolbarButton,
        editToolbarItemDialog: AlertDialog
    ) {
        AlertDialog.Builder(this).show {
            title(R.string.remove_toolbar_item)
            positiveButton(R.string.dialog_positive_delete) {
                editToolbarItemDialog.dismiss()
                removeButton(button)
            }
            negativeButton(R.string.dialog_cancel)
        }
    }

    private fun removeButton(button: CustomToolbarButton) {
        val toolbarButtons = toolbarButtons
        toolbarButtons.removeAt(button.index)
        saveToolbarButtons(toolbarButtons)
        updateToolbar()
    }

    private val toolbarDialog: AlertDialog.Builder
        get() = AlertDialog.Builder(this)
            .neutralButton(R.string.help) {
                openUrl(Uri.parse(getString(R.string.link_manual_note_format_toolbar)))
            }
            .negativeButton(R.string.dialog_cancel)

    private fun displayAddToolbarDialog() {
        val v = layoutInflater.inflate(R.layout.note_editor_toolbar_add_custom_item, null)
        toolbarDialog.show {
            title(R.string.add_toolbar_item)
            setView(v)
            positiveButton(R.string.dialog_positive_create) {
                val etIcon = v.findViewById<EditText>(R.id.note_editor_toolbar_item_icon)
                val et = v.findViewById<EditText>(R.id.note_editor_toolbar_before)
                val et2 = v.findViewById<EditText>(R.id.note_editor_toolbar_after)
                addToolbarButton(etIcon.text.toString(), et.text.toString(), et2.text.toString())
            }
        }
    }

    private fun displayEditToolbarDialog(currentButton: CustomToolbarButton) {
        val view = layoutInflater.inflate(R.layout.note_editor_toolbar_edit_custom_item, null)
        val etIcon = view.findViewById<EditText>(R.id.note_editor_toolbar_item_icon)
        val et = view.findViewById<EditText>(R.id.note_editor_toolbar_before)
        val et2 = view.findViewById<EditText>(R.id.note_editor_toolbar_after)
        val btnDelete = view.findViewById<ImageButton>(R.id.note_editor_toolbar_btn_delete)
        etIcon.setText(currentButton.buttonText)
        et.setText(currentButton.prefix)
        et2.setText(currentButton.suffix)
        val editToolbarDialog = toolbarDialog
            .setView(view)
            .positiveButton(R.string.save) {
                editToolbarButton(
                    etIcon.text.toString(),
                    et.text.toString(),
                    et2.text.toString(),
                    currentButton
                )
            }
            .create()
        btnDelete.setOnClickListener {
            suggestRemoveButton(
                currentButton,
                editToolbarDialog
            )
        }
        editToolbarDialog.show()
    }

    private fun setNoteTypePosition() {
        // Set current note type and deck positions in spinners
        val position = allModelIds!!.indexOf(editorNote!!.notetype.getLong("id"))
        // set selection without firing selectionChanged event
        noteTypeSpinner!!.setSelection(position, false)
    }

    private fun updateTags() {
        if (selectedTags == null) {
            selectedTags = ArrayList(0)
        }
        tagsButton!!.text = resources.getString(
            R.string.CardEditorTags,
            getColUnsafe.tags.join(getColUnsafe.tags.canonify(selectedTags!!)).trim { it <= ' ' }.replace(" ", ", ")
        )
    }

    /** Update the list of card templates for current note type  */
    private fun updateCards(model: JSONObject?) {
        Timber.d("updateCards()")
        val tmpls = model!!.getJSONArray("tmpls")
        var cardsList = StringBuilder()
        // Build comma separated list of card names
        Timber.d("updateCards() template count is %s", tmpls.length())
        for (i in 0 until tmpls.length()) {
            var name = tmpls.getJSONObject(i).optString("name")
            // If more than one card, and we have an existing card, underline existing card
            if (!addNote && tmpls.length() > 1 && model === editorNote!!.notetype && currentEditedCard != null && currentEditedCard!!.template(getColUnsafe)
                .optString("name") == name
            ) {
                name = "<u>$name</u>"
            }
            cardsList.append(name)
            if (i < tmpls.length() - 1) {
                cardsList.append(", ")
            }
        }
        // Make cards list red if the number of cards is being reduced
        if (!addNote && tmpls.length() < editorNote!!.notetype.getJSONArray("tmpls").length()) {
            cardsList = StringBuilder("<font color='red'>$cardsList</font>")
        }
        cardsButton!!.text = HtmlCompat.fromHtml(
            resources.getString(R.string.CardEditorCards, cardsList.toString()),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
    }

    private fun updateField(field: FieldEditText?): Boolean {
        val fieldContent = field!!.text?.toString() ?: ""
        val correctedFieldContent = NoteService.convertToHtmlNewline(fieldContent, shouldReplaceNewlines())
        if (editorNote!!.values()[field.ord] != correctedFieldContent) {
            editorNote!!.values()[field.ord] = correctedFieldContent
            return true
        }
        return false
    }

    private fun tagsAsString(tags: List<String>): String {
        return tags.joinToString(" ")
    }

    private val currentlySelectedNotetype: NotetypeJson?
        get() = noteTypeSpinner?.selectedItemPosition?.let { position ->
            allModelIds?.get(position)?.let { modelId ->
                getColUnsafe.notetypes.get(modelId)
            }
        }

    /**
     * Update all the field EditText views based on the currently selected note type and the mModelChangeFieldMap
     */
    private fun updateFieldsFromMap(newNotetype: NotetypeJson?) {
        val type = FieldChangeType.refreshWithMap(newNotetype, modelChangeFieldMap, shouldReplaceNewlines())
        populateEditFields(type, true)
        updateCards(newNotetype)
    }

    /**
     *
     * @return whether or not to allow remapping of fields for current model
     */
    private fun allowFieldRemapping(): Boolean {
        // Map<String, Pair<Integer, JSONObject>> fMapNew = getCol().getModels().fieldMap(getCurrentlySelectedModel())
        return editorNote!!.items().size > 2
    }

    val fieldsFromSelectedNote: Array<Array<String>>
        get() = editorNote!!.items()

    private fun currentNotetypeIsImageOcclusion() =
        currentlySelectedNotetype?.isImageOcclusion == true

    private fun setupImageOcclusionEditor(imagePath: String = "") {
        val kind: String
        val id: Long
        if (addNote) {
            kind = "add"
            // if opened from an intent, the selected note type may not be suitable for IO
            id = if (currentNotetypeIsImageOcclusion()) { currentlySelectedNotetype!!.id } else 0
        } else {
            kind = "edit"
            id = editorNote?.id!!
        }
        val intent = ImageOcclusion.getIntent(this@NoteEditor, kind, id, imagePath)
        requestIOEditorCloser.launch(intent)
    }

    private fun changeNoteType(newId: NoteTypeId) {
        val oldModelId = getColUnsafe.notetypes.current().getLong("id")
        Timber.i("Changing note type to '%d", newId)

        if (oldModelId == newId) {
            return
        }

        val model = getColUnsafe.notetypes.get(newId)
        if (model == null) {
            Timber.w("New model %s not found, not changing note type", newId)
            return
        }

        getColUnsafe.notetypes.setCurrent(model)
        val currentDeck = getColUnsafe.decks.current()
        currentDeck.put("mid", newId)
        getColUnsafe.decks.save(currentDeck)

        // Update deck
        if (!getColUnsafe.config.getBool(ConfigKey.Bool.ADDING_DEFAULTS_TO_CURRENT_DECK)) {
            deckId = model.optLong("did", Consts.DEFAULT_DECK_ID)
        }

        refreshNoteData(FieldChangeType.changeFieldCount(shouldReplaceNewlines()))
        setDuplicateFieldStyles()
        deckSpinnerSelection!!.updateDeckPosition(deckId)
    }

    // ----------------------------------------------------------------------------
    // INNER CLASSES
    // ----------------------------------------------------------------------------
    private inner class SetNoteTypeListener : OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
            // If a new column was selected then change the key used to map from mCards to the column TextView
            // Timber.i("NoteEditor:: onItemSelected() fired on mNoteTypeSpinner");
            // In case the type is changed while adding the card, the menu options need to be invalidated
            invalidateMenu()
            changeNoteType(allModelIds!![pos])
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
            // Do Nothing
        }
    }

    /* Uses only if mCurrentEditedCard is set, so from reviewer or card browser.*/
    private inner class EditNoteTypeListener : OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
            // Get the current model
            val noteModelId = currentEditedCard!!.noteType(getColUnsafe).getLong("id")
            // Get new model
            val newModel = getColUnsafe.notetypes.get(allModelIds!![pos])
            if (newModel == null) {
                Timber.w("newModel %s not found", allModelIds!![pos])
                return
            }
            // Configure the interface according to whether note type is getting changed or not
            if (allModelIds!![pos] != noteModelId) {
                @KotlinCleanup("Check if this ever happens")
                val tmpls = try {
                    newModel.getJSONArray("tmpls")
                } catch (e: Exception) {
                    Timber.w("error in obtaining templates from model %s", allModelIds!![pos])
                    return
                }
                // Initialize mapping between fields of old model -> new model
                val itemsLength = editorNote!!.items().size
                modelChangeFieldMap = HashUtil.hashMapInit(itemsLength)
                for (i in 0 until itemsLength) {
                    modelChangeFieldMap!![i] = i
                }
                // Initialize mapping between cards new model -> old model
                val templatesLength = tmpls.length()
                modelChangeCardMap = HashUtil.hashMapInit(templatesLength)
                for (i in 0 until templatesLength) {
                    if (i < editorNote!!.numberOfCards(getColUnsafe)) {
                        modelChangeCardMap!![i] = i
                    } else {
                        modelChangeCardMap!![i] = null
                    }
                }
                // Update the field text edits based on the default mapping just assigned
                updateFieldsFromMap(newModel)
                // Don't let the user change any other values at the same time as changing note type
                selectedTags = editorNote!!.tags
                updateTags()
                findViewById<View>(R.id.CardEditorTagButton).isEnabled = false
                // ((LinearLayout) findViewById(R.id.CardEditorCardsButton)).setEnabled(false);
                deckSpinnerSelection!!.setEnabledActionBarSpinner(false)
                deckSpinnerSelection!!.updateDeckPosition(currentEditedCard!!.did)
                updateFieldsFromStickyText()
            } else {
                populateEditFields(FieldChangeType.refresh(shouldReplaceNewlines()), false)
                updateCards(currentEditedCard!!.noteType(getColUnsafe))
                findViewById<View>(R.id.CardEditorTagButton).isEnabled = true
                // ((LinearLayout) findViewById(R.id.CardEditorCardsButton)).setEnabled(false);
                deckSpinnerSelection!!.setEnabledActionBarSpinner(true)
            }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
            // Do Nothing
        }
    }

    private fun convertSelectedTextToCloze(textBox: FieldEditText, addClozeType: AddClozeType) {
        var nextClozeIndex = nextClozeIndex
        if (addClozeType == AddClozeType.SAME_NUMBER) {
            nextClozeIndex -= 1
        }
        val prefix = "{{c" + max(1, nextClozeIndex) + "::"
        val suffix = "}}"
        modifyCurrentSelection(TextWrapper(prefix, suffix), textBox)
    }

    private fun hasClozeDeletions(): Boolean {
        return nextClozeIndex > 1
    }

    // BUG: This assumes all fields are inserted as: {{cloze:Text}}
    private val nextClozeIndex: Int
        get() {
            // BUG: This assumes all fields are inserted as: {{cloze:Text}}
            val fieldValues: MutableList<String> = ArrayList(
                editFields!!.size
            )
            for (e in editFields!!) {
                val editable = e!!.text
                val fieldValue = editable?.toString() ?: ""
                fieldValues.add(fieldValue)
            }
            return ClozeUtils.getNextClozeIndex(fieldValues)
        }
    private val isClozeType: Boolean
        get() = currentlySelectedNotetype!!.isCloze

    @VisibleForTesting
    fun startAdvancedTextEditor(index: Int) {
        val field = TextField()
        field.text = getCurrentFieldText(index)
        startMultimediaFieldEditorForField(index, field)
    }

    @VisibleForTesting
    fun setFieldValueFromUi(i: Int, newText: String?) {
        val editText = editFields!![i]
        editText!!.setText(newText)
        EditFieldTextWatcher(i).afterTextChanged(editText.text!!)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getFieldForTest(index: Int): FieldEditText {
        return editFields!![index]!!
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun setCurrentlySelectedModel(mid: NoteTypeId) {
        val position = allModelIds!!.indexOf(mid)
        check(position != -1) { "$mid not found" }
        noteTypeSpinner!!.setSelection(position)
    }

    private inner class EditFieldTextWatcher(private val index: Int) : TextWatcher {
        override fun afterTextChanged(arg0: Editable) {
            isFieldEdited = true
            if (index == 0) {
                setDuplicateFieldStyles()
            }
        }
        override fun beforeTextChanged(arg0: CharSequence, arg1: Int, arg2: Int, arg3: Int) {
            // do nothing
        }

        override fun onTextChanged(arg0: CharSequence, arg1: Int, arg2: Int, arg3: Int) {
            // do nothing
        }
    }

    companion object {
        // DA 2020-04-13 - Refactoring Plans once tested:
        // * There is a difference in functionality depending on whether we are editing
        // * Extract mAddNote and mCurrentEditedCard into inner class. Gate mCurrentEditedCard on edit state.
        // * Possibly subclass
        // * Make this in memory and immutable for metadata, it's hard to reason about our state if we're modifying col
        // * Consider persistence strategy for temporary media. Saving after multimedia edit is probably too early, but
        // we don't want to risk the cache being cleared. Maybe add in functionality to remove from collection if added and
        // the action is cancelled?
        //    public static final String SOURCE_LANGUAGE = "SOURCE_LANGUAGE";
        //    public static final String TARGET_LANGUAGE = "TARGET_LANGUAGE";
        const val SOURCE_TEXT = "SOURCE_TEXT"
        const val TARGET_TEXT = "TARGET_TEXT"
        const val EXTRA_CALLER = "CALLER"
        const val EXTRA_CARD_ID = "CARD_ID"
        const val EXTRA_CONTENTS = "CONTENTS"
        const val EXTRA_TAGS = "TAGS"
        const val EXTRA_ID = "ID"
        const val EXTRA_DID = "DECK_ID"
        const val EXTRA_TEXT_FROM_SEARCH_VIEW = "SEARCH"
        const val EXTRA_EDIT_FROM_CARD_ID = "editCid"
        private const val ACTION_CREATE_FLASHCARD = "org.openintents.action.CREATE_FLASHCARD"
        private const val ACTION_CREATE_FLASHCARD_SEND = "android.intent.action.SEND"
        const val NOTE_CHANGED_EXTRA_KEY = "noteChanged"
        const val RELOAD_REQUIRED_EXTRA_KEY = "reloadRequired"
        const val EXTRA_IMG_OCCLUSION = "image_uri"

        // calling activity
        const val CALLER_NO_CALLER = 0
        const val CALLER_EDIT = 1
        const val CALLER_STUDYOPTIONS = 2
        const val CALLER_DECKPICKER = 3
        const val CALLER_REVIEWER_ADD = 11
        const val CALLER_CARDBROWSER_ADD = 7
        const val CALLER_NOTEEDITOR = 8
        const val CALLER_PREVIEWER_EDIT = 9
        const val CALLER_NOTEEDITOR_INTENT_ADD = 10
        const val RESULT_UPDATED_IO_NOTE = 11
        const val CALLER_IMG_OCCLUSION = 12
        const val CALLER_ADD_IMAGE = 13
        const val INSTANT_NOTE_EDITOR = 14

        // preferences keys
        const val PREF_NOTE_EDITOR_SCROLL_TOOLBAR = "noteEditorScrollToolbar"
        private const val PREF_NOTE_EDITOR_SHOW_TOOLBAR = "noteEditorShowToolbar"
        private const val PREF_NOTE_EDITOR_NEWLINE_REPLACE = "noteEditorNewlineReplace"
        private const val PREF_NOTE_EDITOR_CAPITALIZE = "note_editor_capitalize"
        private const val PREF_NOTE_EDITOR_FONT_SIZE = "note_editor_font_size"
        private const val PREF_NOTE_EDITOR_CUSTOM_BUTTONS = "note_editor_custom_buttons"

        private fun shouldReplaceNewlines(): Boolean {
            return AnkiDroidApp.instance.sharedPrefs()
                .getBoolean(PREF_NOTE_EDITOR_NEWLINE_REPLACE, true)
        }

        @VisibleForTesting
        @CheckResult
        fun intentLaunchedWithImage(intent: Intent): Boolean {
            if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW) return false
            if (ImportUtils.isInvalidViewIntent(intent)) return false
            return intent.resolveMimeType()?.startsWith("image/") == true
        }

        private fun shouldHideToolbar(): Boolean {
            return !AnkiDroidApp.instance.sharedPrefs()
                .getBoolean(PREF_NOTE_EDITOR_SHOW_TOOLBAR, true)
        }
    }
}
