package org.wikipedia.readinglist

import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.wikipedia.Constants
import org.wikipedia.Constants.InvokeSource
import org.wikipedia.R
import org.wikipedia.activity.BaseActivity
import org.wikipedia.analytics.eventplatform.ReadingListsAnalyticsHelper
import org.wikipedia.analytics.eventplatform.RecommendedReadingListEvent
import org.wikipedia.auth.AccountUtil
import org.wikipedia.compose.theme.BaseTheme
import org.wikipedia.concurrency.FlowEventBus
import org.wikipedia.database.AppDatabase
import org.wikipedia.databinding.FragmentReadingListsBinding
import org.wikipedia.events.ArticleSavedOrDeletedEvent
import org.wikipedia.events.NewRecommendedReadingListEvent
import org.wikipedia.feed.FeedFragment
import org.wikipedia.history.HistoryEntry
import org.wikipedia.history.SearchActionModeCallback
import org.wikipedia.main.MainActivity
import org.wikipedia.main.MainFragment
import org.wikipedia.page.ExclusiveBottomSheetPresenter
import org.wikipedia.page.PageActivity
import org.wikipedia.page.PageAvailableOfflineHandler
import org.wikipedia.readinglist.database.ReadingList
import org.wikipedia.readinglist.database.ReadingListPage
import org.wikipedia.readinglist.database.RecommendedPage
import org.wikipedia.readinglist.recommended.RecommendedReadingListHelper
import org.wikipedia.readinglist.recommended.RecommendedReadingListOnboardingActivity
import org.wikipedia.readinglist.recommended.RecommendedReadingListUpdateFrequency
import org.wikipedia.readinglist.sync.ReadingListSyncAdapter
import org.wikipedia.readinglist.sync.ReadingListSyncEvent
import org.wikipedia.settings.Prefs
import org.wikipedia.settings.RemoteConfig
import org.wikipedia.util.DeviceUtil
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.ResourceUtil
import org.wikipedia.util.ShareUtil
import org.wikipedia.util.StringUtil
import org.wikipedia.util.log.L
import org.wikipedia.views.CircularProgressBar
import org.wikipedia.views.DefaultViewHolder
import org.wikipedia.views.DrawableItemDecoration
import org.wikipedia.views.MultiSelectActionModeCallback
import org.wikipedia.views.MultiSelectActionModeCallback.Companion.isTagType
import org.wikipedia.views.PageItemView
import org.wikipedia.views.ReadingListsOverflowView

class ReadingListsFragment : Fragment(), SortReadingListsDialog.Callback, ReadingListItemActionsDialog.Callback {
    private var _binding: FragmentReadingListsBinding? = null
    private val binding get() = _binding!!
    private var displayedLists = listOf<Any>()
    private val adapter = ReadingListAdapter()
    private val readingListItemCallback = ReadingListItemCallback()
    private val readingListPageItemCallback = ReadingListPageItemCallback()
    private val searchActionModeCallback = ReadingListsSearchCallback()
    private val multiSelectModeCallback = MultiSelectCallback()
    private var actionMode: ActionMode? = null
    private val overflowCallback = OverflowCallback()
    private var currentSearchQuery: String? = null
    private var selectMode: Boolean = false
    private var importMode: Boolean = false
    private var recentPreviewSavedReadingList: ReadingList? = null
    private var shouldShowImportedSnackbar = false

    val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == AppCompatActivity.RESULT_OK) {
            it.data?.data?.let { uri ->
                onListsImportResult(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecommendedReadingListEvent.submit("impression", "rrl_saved")
        retainInstance = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReadingListsBinding.inflate(inflater, container, false)
        binding.searchEmptyView.setEmptyText(R.string.search_reading_lists_no_results)
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(DrawableItemDecoration(requireContext(), R.attr.list_divider))
        setUpScrollListener()
        binding.swipeRefreshLayout.setOnRefreshListener { refreshSync(this, binding.swipeRefreshLayout) }
        if (RemoteConfig.config.disableReadingListSync) {
            binding.swipeRefreshLayout.isEnabled = false
        }
        binding.searchEmptyView.visibility = View.GONE
        enableLayoutTransition(true)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                FlowEventBus.events.collectLatest { event ->
                    when (event) {
                        is ReadingListSyncEvent -> {
                            binding.recyclerView.post {
                                if (isAdded) {
                                    updateLists(currentSearchQuery, !currentSearchQuery.isNullOrEmpty() || recentPreviewSavedReadingList != null)
                                }
                            }
                        }
                        is ArticleSavedOrDeletedEvent -> {
                            if (event.isAdded) {
                                if (Prefs.readingListsPageSaveCount < SAVE_COUNT_LIMIT) {
                                    showReadingListsSyncDialog()
                                    Prefs.readingListsPageSaveCount += 1
                                }
                            }
                        }
                    }
                }
            }
        }

        return binding.root
    }

    private fun setUpScrollListener() {
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                (requireActivity() as MainActivity).updateToolbarElevation(binding.recyclerView.computeVerticalScrollOffset() != 0)
            }
        })
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        binding.recyclerView.clearOnScrollListeners()
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()

        updateLists()
        ReadingListsAnalyticsHelper.logListsShown(requireContext(), displayedLists.size)
        requireActivity().invalidateOptionsMenu()
    }

    override fun onPause() {
        super.onPause()
        actionMode?.finish()
    }

    override fun onToggleItemOffline(pageId: Long) {
        val page = getPageById(pageId) ?: return
        ReadingListBehaviorsUtil.togglePageOffline(requireActivity() as AppCompatActivity, page) { this.updateLists() }
    }

    override fun onShareItem(pageId: Long) {
        val page = getPageById(pageId) ?: return
        ShareUtil.shareText(requireActivity(), ReadingListPage.toPageTitle(page))
    }

    override fun onAddItemToOther(pageId: Long) {
        val page = getPageById(pageId) ?: return
        ExclusiveBottomSheetPresenter.show(childFragmentManager,
                AddToReadingListDialog.newInstance(ReadingListPage.toPageTitle(page), InvokeSource.READING_LIST_ACTIVITY))
    }

    override fun onMoveItemToOther(pageId: Long) {
        val page = getPageById(pageId) ?: return
        ExclusiveBottomSheetPresenter.show(childFragmentManager,
                MoveToReadingListDialog.newInstance(page.listId, ReadingListPage.toPageTitle(page), InvokeSource.READING_LIST_ACTIVITY))
    }

    override fun onSelectItem(pageId: Long) {
        // ignore
    }

    override fun onDeleteItem(pageId: Long) {
        val page = getPageById(pageId) ?: return
        ReadingListBehaviorsUtil.deletePages(requireActivity() as AppCompatActivity, ReadingListBehaviorsUtil.getListsContainPage(page), page, { this.updateLists() }) { this.updateLists() }
    }

    private fun getPageById(id: Long): ReadingListPage? {
        return displayedLists.firstOrNull { it is ReadingListPage && it.id == id } as ReadingListPage?
    }

    private inner class OverflowCallback : ReadingListsOverflowView.Callback {
        override fun sortByClick() {
            ExclusiveBottomSheetPresenter.show(childFragmentManager,
                    SortReadingListsDialog.newInstance(Prefs.getReadingListSortMode(ReadingList.SORT_BY_NAME_ASC)))
        }

        override fun createNewListClick() {
            val existingTitles = displayedLists.filterIsInstance<ReadingList>().map { it.title }
            ReadingListTitleDialog.readingListTitleDialog(requireActivity(), getString(R.string.reading_list_name_sample), "",
                    existingTitles, callback = object : ReadingListTitleDialog.Callback {
                    override fun onSuccess(text: String, description: String) {
                        AppDatabase.instance.readingListDao().createList(text, description)
                        updateLists()
                    }
                }).show()
        }

        override fun importNewList() {
            var filePickerIntent = Intent(Intent.ACTION_GET_CONTENT)
            filePickerIntent.type = "application/json"
            filePickerIntent = Intent.createChooser(filePickerIntent, getString(R.string.reading_lists_import_file_picker_title))
            filePickerLauncher.launch(filePickerIntent)
        }

        override fun selectListClick() {
            beginMultiSelect()
            adapter.notifyDataSetChanged()
        }

        override fun refreshClick() {
            binding.swipeRefreshLayout.isRefreshing = true
            refreshSync(this@ReadingListsFragment, binding.swipeRefreshLayout)
        }
    }

    private fun sortListsBy(option: Int) {
        when (option) {
            ReadingList.SORT_BY_NAME_DESC -> Prefs.setReadingListSortMode(ReadingList.SORT_BY_NAME_DESC)
            ReadingList.SORT_BY_RECENT_DESC -> Prefs.setReadingListSortMode(ReadingList.SORT_BY_RECENT_DESC)
            ReadingList.SORT_BY_RECENT_ASC -> Prefs.setReadingListSortMode(ReadingList.SORT_BY_RECENT_ASC)
            ReadingList.SORT_BY_NAME_ASC -> Prefs.setReadingListSortMode(ReadingList.SORT_BY_NAME_ASC)
            else -> Prefs.setReadingListSortMode(ReadingList.SORT_BY_NAME_ASC)
        }
        updateLists()
    }

    private fun enableLayoutTransition(enable: Boolean) {
        if (enable) {
            binding.contentContainer.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)
            binding.emptyContainer.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)
        } else {
            binding.contentContainer.layoutTransition.disableTransitionType(LayoutTransition.CHANGING)
            binding.emptyContainer.layoutTransition.disableTransitionType(LayoutTransition.CHANGING)
        }
    }

    fun updateLists() {
        updateLists(currentSearchQuery, !currentSearchQuery.isNullOrEmpty())
    }

    fun startSearchActionMode() {
        (requireActivity() as AppCompatActivity).startSupportActionMode(searchActionModeCallback)
    }

    fun showReadingListsOverflowMenu() {
        ReadingListsOverflowView(requireContext()).show((requireActivity() as MainActivity).getToolbar().findViewById(R.id.menu_overflow_button), overflowCallback)
    }

    private fun updateLists(searchQuery: String?, forcedRefresh: Boolean) {
        maybeShowOnboarding(searchQuery)
        ReadingListBehaviorsUtil.searchListsAndPages(lifecycleScope, searchQuery) { lists ->
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int {
                    return lists.size
                }

                override fun getNewListSize(): Int {
                    return lists.size
                }

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    if (displayedLists.size <= oldItemPosition || lists.size <= newItemPosition) {
                        return false
                    }
                    return (displayedLists[oldItemPosition] is ReadingList && lists[newItemPosition] is ReadingList &&
                            (displayedLists[oldItemPosition] as ReadingList).id == (lists[newItemPosition] as ReadingList).id)
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    if (displayedLists.size <= oldItemPosition || lists.size <= newItemPosition) {
                        return false
                    }
                    return (displayedLists[oldItemPosition] is ReadingList &&
                            (displayedLists[oldItemPosition] as ReadingList).compareTo(lists[newItemPosition]))
                }
            })

            // if the default list is empty, then removes it.
            if (lists.size == 1 && lists[0] is ReadingList &&
                    (lists[0] as ReadingList).isDefault &&
                    (lists[0] as ReadingList).pages.isEmpty()) {
                lists.removeAt(0)
            }

            // If the number of lists has changed, just invalidate everything, as a
            // simple way to get the bottom item margin to apply to the correct item.
            val invalidateAll = (importMode || forcedRefresh || displayedLists.size != lists.size ||
                    (!currentSearchQuery.isNullOrEmpty() && !searchQuery.isNullOrEmpty() && currentSearchQuery != searchQuery))

            lifecycleScope.launch {
                // Asynchronous update of lists affects the multiselect process
                if (!isTagType(actionMode)) {
                    displayedLists = lists
                }

                if (invalidateAll) {
                    adapter.notifyDataSetChanged()
                } else {
                    result.dispatchUpdatesTo(adapter)
                }

                recentPreviewSavedReadingList = displayedLists.filterIsInstance<ReadingList>()
                    .find { it.id == Prefs.readingListRecentReceivedId }?.also { shouldShowImportedSnackbar = true }

                binding.swipeRefreshLayout.isRefreshing = false
                maybeShowListLimitMessage()
                updateEmptyState(searchQuery)
                maybeDeleteListFromIntent()
                maybeShowPreviewSavedReadingListsSnackbar()
                currentSearchQuery = searchQuery
                maybeTurnOffImportMode(lists.filterIsInstance<ReadingList>().toMutableList())

                // Recommended Reading List discover card
                val recommendedArticles = AppDatabase.instance.recommendedPageDao().getNewRecommendedPages()
                if (RecommendedReadingListHelper.readyToGenerateList() && recommendedArticles.isNotEmpty()) {
                    setupRecommendedReadingListDiscoverCardView(recommendedArticles)
                } else {
                    binding.discoverCardView.isVisible = false
                }
            }
        }
    }

    private fun maybeTurnOffImportMode(lists: MutableList<ReadingList>) {
        if (!importMode) {
            return
        }
        importMode = lists.any { it.pages.any { pages -> pages.sizeBytes == 0L } }
    }

    private fun maybeShowListLimitMessage() {
        if (actionMode == null && displayedLists.size >= Constants.MAX_READING_LISTS_LIMIT) {
            val message = getString(R.string.reading_lists_limit_message)
            FeedbackUtil.makeSnackbar(requireActivity(), message).show()
        }
    }

    private fun updateEmptyState(searchQuery: String?) {
        if (searchQuery.isNullOrEmpty()) {
            binding.searchEmptyView.visibility = View.GONE
            setUpEmptyContainer()
            setEmptyContainerVisibility(displayedLists.isEmpty() && !binding.onboardingView.isVisible)
        } else {
            binding.searchEmptyView.visibility = if (displayedLists.isEmpty()) View.VISIBLE else View.GONE
            setEmptyContainerVisibility(false)
        }
    }

    private fun setEmptyContainerVisibility(visible: Boolean) {
        if (visible) {
            binding.emptyContainer.visibility = View.VISIBLE
            requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        } else {
            binding.emptyContainer.visibility = View.GONE
        }
    }

    private fun setUpEmptyContainer() {
        if (displayedLists.size == 1 && displayedLists[0] is ReadingList &&
                (displayedLists[0] as ReadingList).isDefault &&
                (displayedLists[0] as ReadingList).pages.isNotEmpty()) {
            binding.emptyTitle.text = getString(R.string.no_user_lists_title)
            binding.emptyMessage.text = getString(R.string.no_user_lists_msg)
        } else {
            binding.emptyTitle.text = getString(R.string.saved_list_empty_title)
            binding.emptyMessage.text = getString(R.string.reading_lists_empty_message)
        }
    }

    override fun onSortOptionClick(position: Int) {
        sortListsBy(position)
    }

    private inner class ReadingListItemHolder(itemView: ReadingListItemView) : DefaultViewHolder<View>(itemView) {
        fun bindItem(readingList: ReadingList) {
            view.setReadingList(readingList, ReadingListItemView.Description.SUMMARY, selectMode,
                newImport = readingList.id == recentPreviewSavedReadingList?.id)
            view.setSearchQuery(currentSearchQuery)
            view.saveClickListener = View.OnClickListener {
                startActivity(ReadingListActivity.newIntent(requireActivity(), ReadingListMode.PREVIEW))
            }
        }

        override val view get() = itemView as ReadingListItemView
    }

    private inner class ReadingListPageItemHolder(itemView: PageItemView<ReadingListPage>) : DefaultViewHolder<PageItemView<ReadingListPage>>(itemView) {
        fun bindItem(page: ReadingListPage) {
            view.item = page
            view.setTitle(page.displayTitle)
            view.setTitleMaxLines(2)
            view.setTitleEllipsis()
            view.setDescription(page.description)
            view.setDescriptionMaxLines(2)
            view.setDescriptionEllipsis()
            view.setImageUrl(page.thumbUrl)
            view.isSelected = page.selected
            view.setSecondaryActionIcon(if (page.saving) R.drawable.ic_download_in_progress else R.drawable.ic_download_circle_gray_24dp, !page.offline || page.saving)
            view.setCircularProgressVisibility(page.downloadProgress > 0 && page.downloadProgress < CircularProgressBar.MAX_PROGRESS)
            view.setProgress(if (page.downloadProgress == CircularProgressBar.MAX_PROGRESS) 0 else page.downloadProgress)
            view.setActionHint(R.string.reading_list_article_make_offline)
            view.setSearchQuery(currentSearchQuery)
            view.setUpChipGroup(ReadingListBehaviorsUtil.getListsContainPage(page))
            PageAvailableOfflineHandler.check(page) { view.setViewsGreyedOut(!it) }
        }
    }

    private inner class ReadingListAdapter : RecyclerView.Adapter<DefaultViewHolder<*>>() {
        override fun getItemViewType(position: Int): Int {
            return if (displayedLists[position] is ReadingList) {
                VIEW_TYPE_ITEM
            } else {
                VIEW_TYPE_PAGE_ITEM
            }
        }

        override fun getItemCount(): Int {
            return displayedLists.size
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DefaultViewHolder<*> {
            return if (viewType == VIEW_TYPE_ITEM) {
                ReadingListItemHolder(ReadingListItemView(requireContext()))
            } else {
                ReadingListPageItemHolder(PageItemView(requireContext()))
            }
        }

        override fun onBindViewHolder(holder: DefaultViewHolder<*>, pos: Int) {
            if (holder is ReadingListItemHolder) {
                holder.bindItem(displayedLists[pos] as ReadingList)
            } else {
                (holder as ReadingListPageItemHolder).bindItem(displayedLists[pos] as ReadingListPage)
            }
        }

        override fun onViewAttachedToWindow(holder: DefaultViewHolder<*>) {
            super.onViewAttachedToWindow(holder)
            if (holder is ReadingListItemHolder) {
                holder.view.callback = readingListItemCallback
            } else {
                (holder as ReadingListPageItemHolder).view.callback = readingListPageItemCallback
            }
        }

        override fun onViewDetachedFromWindow(holder: DefaultViewHolder<*>) {
            if (holder is ReadingListItemHolder) {
                holder.view.callback = null
            } else {
                (holder as ReadingListPageItemHolder).view.callback = null
            }
            super.onViewDetachedFromWindow(holder)
        }
    }

    private inner class ReadingListItemCallback : ReadingListItemView.Callback {
        override fun onClick(readingList: ReadingList) {
            if (isTagType(actionMode)) {
                toggleSelectList(readingList)
            } else {
                actionMode?.finish()
                RecommendedReadingListEvent.submit("open_list_click", "rrl_saved")
                startActivity(ReadingListActivity.newIntent(requireContext(), readingList))
            }
        }

        override fun onRename(readingList: ReadingList) {
            if (readingList.isDefault) {
                L.w("Attempted to rename default list.")
                return
            }
            ReadingListBehaviorsUtil.renameReadingList(requireActivity(), readingList) {
                ReadingListSyncAdapter.manualSync()
                updateLists(currentSearchQuery, true)
            }
        }

        override fun onDelete(readingList: ReadingList) {
            ReadingListBehaviorsUtil.deleteReadingList(requireActivity(), readingList, true) {
                ReadingListBehaviorsUtil.showDeleteListUndoSnackbar(requireActivity(), readingList) { updateLists() }
                updateLists()
            }
        }

        override fun onSaveAllOffline(readingList: ReadingList) {
            ReadingListBehaviorsUtil.savePagesForOffline(requireActivity(), readingList.pages) { updateLists(currentSearchQuery, true) }
        }

        override fun onRemoveAllOffline(readingList: ReadingList) {
            ReadingListBehaviorsUtil.removePagesFromOffline(requireActivity(), readingList.pages) { updateLists(currentSearchQuery, true) }
        }

        override fun onSelectList(readingList: ReadingList) {
            if (!isTagType(actionMode)) {
                beginMultiSelect()
            }
            toggleSelectList(readingList)
        }

        override fun onChecked(readingList: ReadingList) {
            toggleSelectList(readingList)
        }

        override fun onShare(readingList: ReadingList) {
            ReadingListsShareHelper.shareReadingList(requireActivity() as AppCompatActivity, readingList)
        }
    }

    private fun toggleSelectList(readingList: ReadingList?) {
        readingList?.let {
            displayedLists.filterIsInstance<ReadingList>().forEach { list ->
                if (list.title == readingList.title) {
                    list.selected = !list.selected
                }
            }
            actionMode?.invalidate()
            adapter.notifyDataSetChanged()
        }
    }

    private fun beginMultiSelect() {
        if (!isTagType(actionMode)) {
            selectMode = true
            (requireActivity() as AppCompatActivity).startSupportActionMode(multiSelectModeCallback)
        }
    }

    private fun finishActionMode() {
        actionMode?.finish()
    }

    private inner class ReadingListPageItemCallback : PageItemView.Callback<ReadingListPage?> {
        override fun onClick(item: ReadingListPage?) {
            item?.let {
                val title = ReadingListPage.toPageTitle(it)
                val entry = HistoryEntry(title, HistoryEntry.SOURCE_READING_LIST)
                it.touch()
                ReadingListBehaviorsUtil.updateReadingListPage(item)
                startActivity(PageActivity.newIntentForCurrentTab(requireContext(), entry, entry.title))
            }
        }

        override fun onLongClick(item: ReadingListPage?): Boolean {
            item?.let {
                ExclusiveBottomSheetPresenter.show(childFragmentManager,
                        ReadingListItemActionsDialog.newInstance(ReadingListBehaviorsUtil.getListsContainPage(it), it.id, actionMode != null))
                return true
            }
            return false
        }

        override fun onActionClick(item: ReadingListPage?, view: View) {
            item?.let {
                if (Prefs.isDownloadOnlyOverWiFiEnabled && !DeviceUtil.isOnWiFi &&
                        it.status == ReadingListPage.STATUS_QUEUE_FOR_SAVE) {
                    it.offline = false
                }
                if (it.saving) {
                    Toast.makeText(context, R.string.reading_list_article_save_in_progress, Toast.LENGTH_LONG).show()
                } else {
                    ReadingListBehaviorsUtil.toggleOffline(requireActivity(), it) { adapter.notifyDataSetChanged() }
                }
            }
        }

        override fun onListChipClick(readingList: ReadingList) {
            startActivity(ReadingListActivity.newIntent(requireContext(), readingList))
        }
    }

    private fun maybeDeleteListFromIntent() {
        if (requireActivity().intent.hasExtra(Constants.INTENT_EXTRA_DELETE_READING_LIST)) {
            val titleToDelete = requireActivity().intent
                    .getStringExtra(Constants.INTENT_EXTRA_DELETE_READING_LIST)
            requireActivity().intent.removeExtra(Constants.INTENT_EXTRA_DELETE_READING_LIST)
            displayedLists.forEach {
                if (it is ReadingList && it.title == titleToDelete) {
                    ReadingListBehaviorsUtil.deleteReadingList(requireActivity(), it, false) {
                        ReadingListBehaviorsUtil.showDeleteListUndoSnackbar(requireActivity(), it) { updateLists() }
                        updateLists()
                    }
                }
            }
        }
    }

    private val selectedLists
        get() = displayedLists.filterIsInstance<ReadingList>().filter { it.selected }

    private inner class MultiSelectCallback : MultiSelectActionModeCallback() {
        private val allSelected get() = selectedLists.size == displayedLists.count { it is ReadingList }
        private val noneSelected get() = selectedLists.isEmpty()

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            super.onCreateActionMode(mode, menu)
            mode.menuInflater.inflate(R.menu.menu_action_mode_reading_lists, menu)
            actionMode = mode
            val deleteItem = menu.findItem(R.id.menu_delete_selected)
            deleteItem.isEnabled = false
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val listsSelected = selectedLists
            val onlyDefaultSelected = listsSelected.size == 1 && listsSelected[0].isDefault
            mode.title = if (listsSelected.isEmpty()) "" else getString(R.string.multi_select_items_selected, listsSelected.size)
            val fullOpacity = 255
            val halfOpacity = 80
            val deleteItem = menu.findItem(R.id.menu_delete_selected)
            val exportItem = menu.findItem(R.id.menu_export_selected)
            val exportItemTitleColor = ResourceUtil.getThemedColor(requireContext(), R.attr.progressive_color)
            exportItem.title = buildSpannedString {
                color(ColorUtils.setAlphaComponent(exportItemTitleColor,
                    if (listsSelected.isEmpty()) halfOpacity else fullOpacity)) {
                    append(exportItem.title)
                }
            }
            deleteItem.icon?.alpha = if (listsSelected.isEmpty() || onlyDefaultSelected) halfOpacity else fullOpacity
            exportItem.isEnabled = listsSelected.isNotEmpty()
            deleteItem.isEnabled = listsSelected.isNotEmpty() && !onlyDefaultSelected

            val selectButton = menu.findItem(R.id.menu_select)
            selectButton.setIcon(when {
                noneSelected -> R.drawable.ic_outline_library_add_check_24
                allSelected -> R.drawable.ic_deselect_all
                else -> R.drawable.ic_select_indeterminate
            })
            selectButton.title = when {
                noneSelected -> getString(R.string.notifications_menu_check_all)
                allSelected -> getString(R.string.notifications_menu_uncheck_all)
                else -> ""
            }
            return super.onPrepareActionMode(mode, menu)
        }

        override fun onActionItemClicked(mode: ActionMode, menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.menu_delete_selected -> {
                    onDeleteSelected()
                    return true
                }
                R.id.menu_export_selected -> {
                    if (selectedLists.isEmpty()) {
                        Toast.makeText(context, getString(R.string.reading_lists_export_select_lists_message),
                            Toast.LENGTH_SHORT).show()
                        return true
                    }
                    ReadingListsExportImportHelper.exportLists(activity as BaseActivity, selectedLists)
                    finishActionMode()
                    return true
                }
                R.id.menu_select -> {
                    when {
                        allSelected -> unselectAllLists()
                        else -> selectAllLists()
                    }
                    mode.invalidate()
                }
            }
            return false
        }

        override fun onDeleteSelected() {
            selectedLists.let {
                ReadingListBehaviorsUtil.deleteReadingLists(requireActivity(), it) {
                    ReadingListBehaviorsUtil.showDeleteListsUndoSnackbar(requireActivity(), it) { updateLists() }
                    finishActionMode()
                    updateLists()
                }
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selectMode = false
            unselectAllLists()
            actionMode = null
            super.onDestroyActionMode(mode)
        }
    }

    private fun unselectAllLists() {
        selectedLists.let {
            it.forEach { list ->
                list.selected = false
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun selectAllLists() {
         displayedLists.let {
            displayedLists.filterIsInstance<ReadingList>()
                .filter { !it.selected }
                .onEach { it.selected = true }
        }
        adapter.notifyDataSetChanged()
    }

    private inner class ReadingListsSearchCallback : SearchActionModeCallback() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            // searching delay will let the animation cannot catch the update of list items, and will cause crashes
            enableLayoutTransition(false)
            binding.onboardingView.isVisible = false
            if (isAdded) {
                (requireParentFragment() as MainFragment).setBottomNavVisible(false)
            }
            return super.onCreateActionMode(mode, menu)
        }

        override fun onQueryChange(s: String) {
            updateLists(s.trim(), false)
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            super.onDestroyActionMode(mode)
            enableLayoutTransition(true)
            actionMode = null
            currentSearchQuery = null
            if (isAdded) {
                (requireParentFragment() as MainFragment).setBottomNavVisible(true)
            }
            updateLists()
        }

        override fun getSearchHintString(): String {
            return requireContext().resources.getString(R.string.filter_hint_filter_my_lists_and_articles)
        }

        override fun getParentContext(): Context {
            return requireContext()
        }
    }

    private fun showReadingListsSyncDialog() {
        if (!Prefs.isReadingListSyncEnabled) {
            if (AccountUtil.isLoggedIn) {
                ReadingListSyncBehaviorDialogs.promptEnableSyncDialog(requireActivity())
            } else {
                if (recentPreviewSavedReadingList == null) {
                    ReadingListSyncBehaviorDialogs.promptLogInToSyncDialog(requireActivity())
                }
            }
        }
    }

    private fun maybeShowPreviewSavedReadingListsSnackbar() {
        if (shouldShowImportedSnackbar) {
            ReadingListsAnalyticsHelper.logReceiveFinish(requireContext(), recentPreviewSavedReadingList)
            FeedbackUtil.makeSnackbar(requireActivity(), getString(R.string.reading_lists_preview_saved_snackbar))
                .setAction(R.string.suggested_edits_article_cta_snackbar_action) {
                    recentPreviewSavedReadingList?.let {
                        startActivity(ReadingListActivity.newIntent(requireContext(), it))
                    }
                }
                .show()
            shouldShowImportedSnackbar = false
            Prefs.receiveReadingListsData = null
            Prefs.readingListRecentReceivedId = -1L
        }
    }

    private fun maybeShowOnboarding(searchQuery: String?) {
        if (!searchQuery.isNullOrEmpty()) {
            binding.onboardingView.isVisible = false
            return
        }
        if (!Prefs.isRecommendedReadingListOnboardingShown) {
            RecommendedReadingListEvent.submit("impression", "rrl_saved_prompt")
            binding.onboardingView.setMessageLabel(getString(R.string.recommended_reading_list_onboarding_card_new))
            binding.onboardingView.setMessageTitle(getString(R.string.recommended_reading_list_onboarding_card_title))
            binding.onboardingView.setMessageText(getString(R.string.recommended_reading_list_onboarding_card_message))
            binding.onboardingView.setImageResource(-1, false)
            binding.onboardingView.setPositiveButton(R.string.recommended_reading_list_onboarding_card_positive_button, {
                startActivity(RecommendedReadingListOnboardingActivity.newIntent(requireContext()))
                RecommendedReadingListEvent.submit("enter_click", "rrl_saved_prompt")
            }, true)
            binding.onboardingView.setNegativeButton(R.string.recommended_reading_list_onboarding_card_negative_button, {
                binding.onboardingView.isVisible = false
                Prefs.isRecommendedReadingListOnboardingShown = true
                updateEmptyState(null)
                FeedbackUtil.showMessage(this@ReadingListsFragment, getString(R.string.recommended_reading_list_onboarding_card_negative_snackbar))
                RecommendedReadingListEvent.submit("nothanks_click", "rrl_saved_prompt")
            }, false)
            binding.onboardingView.isVisible = true
        } else if ((AccountUtil.isLoggedIn && !AccountUtil.isTemporaryAccount) && !Prefs.isReadingListSyncEnabled &&
                Prefs.isReadingListSyncReminderEnabled && !RemoteConfig.config.disableReadingListSync) {
            binding.onboardingView.setMessageLabel(null)
            binding.onboardingView.setMessageTitle(getString(R.string.reading_lists_sync_reminder_title))
            binding.onboardingView.setMessageText(StringUtil.fromHtml(getString(R.string.reading_lists_sync_reminder_text)).toString())
            binding.onboardingView.setImageResource(ResourceUtil.getThemedAttributeId(requireContext(), R.attr.sync_reading_list_prompt_drawable), true)
            binding.onboardingView.setPositiveButton(R.string.reading_lists_sync_reminder_action, { ReadingListSyncAdapter.setSyncEnabledWithSetup() }, true)
            binding.onboardingView.setNegativeButton(R.string.reading_lists_ignore_button, {
                binding.onboardingView.isVisible = false
                Prefs.isReadingListSyncReminderEnabled = false
            }, false)
            binding.onboardingView.isVisible = true
        } else if ((!AccountUtil.isLoggedIn || AccountUtil.isTemporaryAccount) && Prefs.isReadingListLoginReminderEnabled && !RemoteConfig.config.disableReadingListSync) {
            binding.onboardingView.setMessageLabel(null)
            binding.onboardingView.setMessageTitle(getString(R.string.reading_list_login_reminder_title))
            binding.onboardingView.setMessageText(getString(R.string.reading_lists_login_reminder_text))
            binding.onboardingView.setImageResource(ResourceUtil.getThemedAttributeId(requireContext(), R.attr.sync_reading_list_prompt_drawable), true)
            binding.onboardingView.setPositiveButton(R.string.reading_lists_login_button, {
                if (isAdded && requireParentFragment() is FeedFragment.Callback) {
                    (requireParentFragment() as FeedFragment.Callback).onLoginRequested()
                }
            }, true)
            binding.onboardingView.setNegativeButton(R.string.reading_lists_ignore_button, {
                binding.onboardingView.isVisible = false
                Prefs.isReadingListLoginReminderEnabled = false
                updateEmptyState(null)
            }, false)
            binding.onboardingView.isVisible = true
        } else {
            binding.onboardingView.isVisible = false
        }
    }

    private fun onListsImportResult(uri: Uri) {
        binding.swipeRefreshLayout.isRefreshing = true
        activity?.contentResolver?.openInputStream(uri)?.use { inputStream ->
            val inputString = inputStream.bufferedReader().use { it.readText() }
            ReadingListsExportImportHelper.importLists(activity as BaseActivity, inputString)
            importMode = true
        }
    }

    private fun setupRecommendedReadingListDiscoverCardView(recommendedArticles: List<RecommendedPage>) {
        binding.discoverCardView.isVisible = true
        binding.discoverCardView.setContent {
            val images by remember(Prefs.recommendedReadingListArticlesNumber, Prefs.recommendedReadingListSource) { mutableStateOf(recommendedArticles.mapNotNull { it.thumbUrl }) }
            var isNewListGenerated by remember { mutableStateOf(Prefs.isNewRecommendedReadingListGenerated) }
            val subtitle = when (AccountUtil.isLoggedIn) {
                true -> { getString(R.string.recommended_reading_list_page_subtitle_made_for, "<b>" + AccountUtil.userName + "</b>") }
                false -> { getString(R.string.recommended_reading_list_page_logged_out_subtitle_made_for_you) }
            }

            LaunchedEffect(Unit) {
                FlowEventBus.events.collect { event ->
                    if (event is NewRecommendedReadingListEvent) {
                        isNewListGenerated = Prefs.isNewRecommendedReadingListGenerated
                    }
                }
            }
            val description = when (Prefs.recommendedReadingListUpdateFrequency) {
                RecommendedReadingListUpdateFrequency.DAILY -> R.string.recommended_reading_list_page_description_daily
                RecommendedReadingListUpdateFrequency.WEEKLY -> R.string.recommended_reading_list_page_description_weekly
                RecommendedReadingListUpdateFrequency.MONTHLY -> R.string.recommended_reading_list_page_description_monthly
            }
            BaseTheme {
                RecommendedReadingListDiscoverCardView(
                    modifier = Modifier
                        .clickable {
                            FlowEventBus.post(NewRecommendedReadingListEvent())
                            startActivity(ReadingListActivity.newIntent(requireActivity(), ReadingListMode.RECOMMENDED))
                        }
                        .padding(16.dp),
                    title = getString(R.string.recommended_reading_list_title),
                    subtitleIcon = R.drawable.ic_wikipedia_w,
                    subtitle = subtitle,
                    description = getString(description),
                    images = images,
                    isNewListGenerated = isNewListGenerated,
                    isUserLoggedIn = AccountUtil.isLoggedIn
                )
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_PAGE_ITEM = 1
        private const val SAVE_COUNT_LIMIT = 3

        fun newInstance(): ReadingListsFragment {
            return ReadingListsFragment()
        }

        fun refreshSync(fragment: Fragment, swipeRefreshLayout: SwipeRefreshLayout) {
            if (!AccountUtil.isLoggedIn || AccountUtil.isTemporaryAccount) {
                ReadingListSyncBehaviorDialogs.promptLogInToSyncDialog(fragment.requireActivity())
                swipeRefreshLayout.isRefreshing = false
            } else {
                Prefs.isReadingListSyncEnabled = true

                // TODO: Change this back to the less forceful manualSyncWithRefresh() when the
                // service-side endpoint is fixed.
                // https://phabricator.wikimedia.org/T351149
                ReadingListSyncAdapter.manualSyncWithForce()
            }
        }
    }
}
