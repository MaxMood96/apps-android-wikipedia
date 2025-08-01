package org.wikipedia.readinglist.sync

import android.content.*
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wikipedia.WikipediaApp
import org.wikipedia.auth.AccountUtil
import org.wikipedia.concurrency.FlowEventBus
import org.wikipedia.csrf.CsrfTokenClient
import org.wikipedia.database.AppDatabase
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.events.ReadingListsEnableDialogEvent
import org.wikipedia.events.ReadingListsEnabledStatusEvent
import org.wikipedia.events.ReadingListsNoLongerSyncedEvent
import org.wikipedia.page.PageTitle
import org.wikipedia.readinglist.database.ReadingList
import org.wikipedia.readinglist.database.ReadingListPage
import org.wikipedia.readinglist.sync.SyncedReadingLists.RemoteReadingList
import org.wikipedia.readinglist.sync.SyncedReadingLists.RemoteReadingListEntry
import org.wikipedia.savedpages.SavedPageSyncService
import org.wikipedia.settings.Prefs
import org.wikipedia.settings.RemoteConfig
import org.wikipedia.util.StringUtil
import org.wikipedia.util.log.L

class ReadingListSyncAdapter(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val extras = getBooleanExtraFromData(inputData)
            if (RemoteConfig.config.disableReadingListSync || !AccountUtil.isLoggedIn || AccountUtil.isTemporaryAccount ||
                !(Prefs.isReadingListSyncEnabled || Prefs.isReadingListsRemoteDeletePending)) {
                L.d("Skipping sync of reading lists.")
                if (extras.containsKey(SYNC_EXTRAS_REFRESHING)) {
                    SavedPageSyncService.sendSyncEvent()
                }
                return@withContext Result.success()
            }
            L.d("Begin sync of reading lists...")
            val listIdsDeleted = Prefs.readingListsDeletedIds.toMutableSet()
            val pageIdsDeleted = Prefs.readingListPagesDeletedIds.toMutableSet()
            var allLocalLists: MutableList<ReadingList>? = null
            val wiki = WikipediaApp.instance.wikiSite
            val client = ReadingListClient(wiki)
            val readingListSyncNotification = ReadingListSyncNotification.instance
            val lastSyncTime = Prefs.readingListsLastSyncTime.orEmpty()
            var shouldSendSyncEvent = extras.containsKey(SYNC_EXTRAS_REFRESHING)
            var shouldRetry = false
            var shouldRetryWithForce = false
            try {
                IN_PROGRESS = true
                var syncEverything = false
                if (extras.containsKey(SYNC_EXTRAS_FORCE_FULL_SYNC) ||
                    Prefs.isReadingListsRemoteDeletePending ||
                    Prefs.isReadingListsRemoteSetupPending) {
                    // reset the remote ID on all lists, since they will need to be recreated next time.
                    L.d("Resetting all lists to un-synced.")
                    syncEverything = true
                    AppDatabase.instance.readingListDao().markAllListsUnsynced()
                    AppDatabase.instance.readingListPageDao().markAllPagesUnsynced()
                    allLocalLists = AppDatabase.instance.readingListDao().getAllLists().toMutableList()
                }

                val csrfToken = CsrfTokenClient.getToken(wiki)

                if (Prefs.isReadingListsRemoteDeletePending) {
                    // Are we scheduled for a teardown? If so, delete everything and bail.
                    L.d("Tearing down remote lists...")
                    client.tearDown(csrfToken)
                    Prefs.isReadingListsRemoteDeletePending = false
                    return@withContext Result.success()
                } else if (Prefs.isReadingListsRemoteSetupPending) {
                    // ...Or are we scheduled for setup?
                    client.setup(csrfToken)
                    Prefs.isReadingListsRemoteSetupPending = false
                }

                // -----------------------------------------------
                // PHASE 1: Sync from remote to local.
                // -----------------------------------------------
                var remoteListsModified = mutableListOf<RemoteReadingList>()
                var remoteEntriesModified = mutableListOf<RemoteReadingListEntry>()
                if (lastSyncTime.isEmpty()) {
                    syncEverything = true
                }

                if (!syncEverything) {
                    try {
                        L.d("Fetching changes from server, since $lastSyncTime")
                        val allChanges = client.getChangesSince(lastSyncTime)
                        allChanges.lists?.let {
                            remoteListsModified = it as MutableList<RemoteReadingList>
                        }
                        allChanges.entries?.let {
                            remoteEntriesModified = it as MutableList<RemoteReadingListEntry>
                        }
                    } catch (t: Throwable) {
                        if (client.isErrorType(t, "too-old")) {
                            // If too much time has elapsed between syncs, then perform a full sync.
                            syncEverything = true
                        } else {
                            throw t
                        }
                    }
                }

                if (allLocalLists == null) {
                    allLocalLists = if (syncEverything) {
                        AppDatabase.instance.readingListDao().getAllLists().toMutableList()
                    } else {
                        AppDatabase.instance.readingListDao().getAllListsWithUnsyncedPages().toMutableList()
                    }
                }

                        // Perform a quick check for whether we'll need to sync all lists
                for (remoteEntry in remoteEntriesModified) {
                    // find the list to which this entry belongs...
                    val eigenLocalList = allLocalLists.find { it.remoteId == remoteEntry.listId }
                    val eigenRemoteList = remoteListsModified.find { it.id == remoteEntry.listId }

                    if (eigenLocalList == null && eigenRemoteList == null) {
                        L.w("Remote entry belongs to an unknown local list. Falling back to full sync.")
                        syncEverything = true
                        break
                    }
                }
                if (syncEverything) {
                    allLocalLists = AppDatabase.instance.readingListDao().getAllLists().toMutableList()
                    L.d("Fetching all lists from server...")
                    remoteListsModified = client.allLists as MutableList<RemoteReadingList>
                }

                // Notify any event consumers that reading lists are, in fact, enabled.
                FlowEventBus.post(ReadingListsEnabledStatusEvent())

                // setup syncing indicator for remote to local
                val remoteItemsTotal = remoteListsModified.size

                // First, update our list hierarchy to match the remote hierarchy.
                for ((remoteItemsSynced, remoteList) in remoteListsModified.withIndex()) {
                    readingListSyncNotification.setNotificationProgress(applicationContext, remoteItemsTotal, remoteItemsSynced)
                    // Find the remote list in our local lists...
                    var localList: ReadingList? = null
                    var upsertNeeded = false
                    for (list in allLocalLists) {
                        if (list.isDefault && remoteList.isDefault) {
                            localList = list
                            if (list.remoteId != remoteList.id) {
                                list.remoteId = remoteList.id
                                upsertNeeded = true
                            }
                            break
                        }
                        if (list.remoteId == remoteList.id) {
                            localList = list
                            break
                        } else if (StringUtil.normalizedEquals(list.title, remoteList.name())) {
                            localList = list
                            localList.remoteId = remoteList.id
                            upsertNeeded = true
                            break
                        }
                    }
                    if (remoteList.isDefault && localList != null && !localList.isDefault) {
                        L.logRemoteError(RuntimeException("Unexpected: remote default list corresponds to local non-default list."))
                        localList = AppDatabase.instance.readingListDao().getDefaultList()
                    }
                    if (remoteList.isDeleted) {
                        if (localList != null && !localList.isDefault) {
                            L.d("Deleting local list " + localList.title)
                            AppDatabase.instance.readingListDao().deleteList(localList, false)
                            AppDatabase.instance.readingListPageDao().markPagesForDeletion(localList, localList.pages, false)
                            allLocalLists.remove(localList)
                            shouldSendSyncEvent = true
                        }
                        continue
                    }
                    if (localList == null) {
                        // A new list needs to be created locally.
                        L.d("Creating local list " + remoteList.name())
                        localList = if (remoteList.isDefault) {
                            L.logRemoteError(RuntimeException("Unexpected: local default list no longer matches remote."))
                            AppDatabase.instance.readingListDao().getDefaultList()
                        } else {
                            AppDatabase.instance.readingListDao().createList(remoteList.name(), remoteList.description())
                        }
                        localList.remoteId = remoteList.id
                        allLocalLists.add(localList)
                        upsertNeeded = true
                    } else {
                        if (!localList.isDefault && !StringUtil.normalizedEquals(localList.title, remoteList.name())) {
                            localList.title = remoteList.name()
                            upsertNeeded = true
                        }
                        if (!localList.isDefault && !StringUtil.normalizedEquals(localList.description, remoteList.description())) {
                            localList.description = remoteList.description()
                            upsertNeeded = true
                        }
                    }
                    if (upsertNeeded) {
                        L.d("Updating info for local list " + localList.title)
                        localList.dirty = false
                        AppDatabase.instance.readingListDao().updateList(localList, false)
                        shouldSendSyncEvent = true
                    }
                    if (syncEverything) {
                        L.d("Fetching all pages in remote list " + remoteList.name())
                        client.getListEntries(remoteList.id).forEach {
                            // TODO: optimization opportunity -- create/update local pages in bulk.
                            createOrUpdatePage(localList, it)
                        }
                        shouldSendSyncEvent = true
                    }
                }
                if (!syncEverything) {
                    for (remoteEntry in remoteEntriesModified) {
                        // find the list to which this entry belongs...
                        val eigenList = allLocalLists.find { it.remoteId == remoteEntry.listId }
                        if (eigenList == null) {
                            L.w("Remote entry belongs to an unknown local list.")
                            continue
                        }
                        shouldSendSyncEvent = true
                        if (remoteEntry.isDeleted) {
                            deletePageByTitle(eigenList, pageTitleFromRemoteEntry(remoteEntry))
                        } else {
                            createOrUpdatePage(eigenList, remoteEntry)
                        }
                    }
                }

                // -----------------------------------------------
                // PHASE 2: Sync from local to remote.
                // -----------------------------------------------

                // Do any remote lists need to be deleted?
                val listIdsToDelete = mutableListOf<Long>()
                listIdsToDelete.addAll(listIdsDeleted)
                for (id in listIdsToDelete) {
                    L.d("Deleting remote list id $id")
                    try {
                        client.deleteList(csrfToken, id)
                    } catch (t: Throwable) {
                        L.w(t)
                        if (!client.isServiceError(t) && !client.isUnavailableError(t)) {
                            throw t
                        }
                    }
                    listIdsDeleted.remove(id)
                }

                // Do any remote pages need to be deleted?
                val pageIdsToDelete = pageIdsDeleted.toMutableSet()

                // Determine if any articles need to be de-duplicated (because of bugs in previous sync inconsistencies)
                if (syncEverything) {
                    allLocalLists.forEach { list ->
                        val distinct = list.pages.distinctBy { pageTitleFromRemoteEntry(remoteEntryFromLocalPage(it)) }
                        val toRemove = list.pages.toMutableSet()
                        toRemove.removeAll(distinct.toSet())
                        if (toRemove.isNotEmpty()) {
                            toRemove.forEach {
                                AppDatabase.instance.readingListPageDao().deleteReadingListPage(it)
                            }
                            pageIdsToDelete.addAll(createIdsForDeletion(list, toRemove))
                        }
                    }
                }

                for (id in pageIdsToDelete) {
                    L.d("Deleting remote page id $id")
                    val listAndPageId = id.split(":").toTypedArray()
                    try {
                        // TODO: optimization opportunity once server starts supporting batch deletes.
                        client.deletePageFromList(csrfToken, listAndPageId[0].toLong(), listAndPageId[1].toLong())
                    } catch (t: Throwable) {
                        L.w(t)
                        if (!client.isServiceError(t) && !client.isUnavailableError(t)) {
                            throw t
                        }
                    }
                    pageIdsDeleted.remove(id)
                }

                // setup syncing indicator for local to remote
                val localItemsTotal = allLocalLists.size

                // Determine whether any remote lists need to be created or updated
                for ((localItemsSynced, localList) in allLocalLists.withIndex()) {
                    readingListSyncNotification.setNotificationProgress(applicationContext, localItemsTotal, localItemsSynced)
                    val remoteList = RemoteReadingList(name = localList.title, description = localList.description)
                    var upsertNeeded = false
                    if (localList.remoteId > 0) {
                        if (!localList.isDefault && localList.dirty) {
                            // Update remote metadata for this list.
                            L.d("Updating info for remote list " + remoteList.name())
                            client.updateList(csrfToken, localList.remoteId, remoteList)
                            upsertNeeded = true
                        }
                    } else if (!localList.isDefault) {
                        // This list needs to be created remotely.
                        L.d("Creating remote list " + remoteList.name())
                        val id = client.createList(csrfToken, remoteList)
                        localList.remoteId = id
                        upsertNeeded = true
                    }
                    if (upsertNeeded) {
                        localList.dirty = false
                        AppDatabase.instance.readingListDao().updateList(localList, false)
                    }
                }
                for (localList in allLocalLists) {
                    val localPages = localList.pages.filter { it.remoteId < 1 }
                    val newEntries = localPages.map { remoteEntryFromLocalPage(it) }
                    // Note: newEntries.size() is guaranteed to be equal to localPages.size()
                    if (newEntries.isEmpty()) {
                        continue
                    }
                    var tryOneAtATime = false
                    try {
                        if (localPages.size == 1) {
                            L.d("Creating new remote page " + localPages[0].displayTitle)
                            localPages[0].remoteId = client.addPageToList(csrfToken, localList.remoteId, newEntries[0])
                            AppDatabase.instance.readingListPageDao().updateReadingListPage(localPages[0])
                        } else {
                            L.d("Creating " + newEntries.size + " new remote pages")
                            val ids = client.addPagesToList(csrfToken, localList.remoteId, newEntries)
                            for (i in ids.indices) {
                                localPages[i].remoteId = ids[i]
                            }
                            AppDatabase.instance.readingListPageDao().updatePages(localPages)
                        }
                    } catch (t: Throwable) {
                        // TODO: optimization opportunity -- if the server can return the ID
                        // of the existing page(s), then we wouldn't need to do a hard sync.

                        // If the page already exists in the remote list, this means that
                        // the contents of this list have diverged from the remote list,
                        // so let's force a full sync.
                        if (client.isErrorType(t, "duplicate-page")) {
                            shouldRetryWithForce = true
                            break
                        } else if (client.isErrorType(t, "entry-limit")) {
                            // TODO: handle more meaningfully than ignoring, for now.
                        } else if (client.isErrorType(t, "no-such-project")) {
                            // Something is malformed in the page domain, but we don't know which page
                            // in the batch caused the error. Therefore, let's retry uploading the pages
                            // one at a time, and single out the one that fails.
                            tryOneAtATime = true
                        } else {
                            throw t
                        }
                    }
                    if (tryOneAtATime) {
                        for (i in localPages.indices) {
                            val localPage = localPages[i]
                            try {
                                L.d("Creating new remote page " + localPage.displayTitle)
                                localPage.remoteId = client.addPageToList(csrfToken, localList.remoteId, newEntries[i])
                            } catch (t: Throwable) {
                                if (client.isErrorType(t, "duplicate-page")) {
                                    shouldRetryWithForce = true
                                    break
                                } else if (client.isErrorType(t, "entry-limit")) {
                                    // TODO: handle more meaningfully than ignoring, for now.
                                } else if (client.isErrorType(t, "no-such-project")) {
                                    // Ignore the error, and give this malformed page a bogus remoteID,
                                    // so that we won't try syncing it again.
                                    localPage.remoteId = Int.MAX_VALUE.toLong()
                                    // ...and also log it:
                                    L.logRemoteError(RuntimeException("Attempted to sync malformed page: ${localPage.wiki}, ${localPage.displayTitle}"))
                                } else {
                                    throw t
                                }
                            }
                        }
                        AppDatabase.instance.readingListPageDao().updatePages(localPages)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                var errorMsg = t
                if (client.isErrorType(t, "not-set-up")) {
                    Prefs.isReadingListSyncEnabled = false
                    if (lastSyncTime.isEmpty()) {
                        // This means that it's our first time attempting to sync, and we see that
                        // syncing isn't enabled on the server. So, let's prompt the user to enable it:
                        FlowEventBus.post(ReadingListsEnableDialogEvent())
                    } else {
                        // This can only mean that our reading lists have been torn down (disabled) by
                        // another client, so we need to notify the user of this development.
                        FlowEventBus.post(ReadingListsNoLongerSyncedEvent())
                    }
                }
                if (client.isErrorType(t, "notloggedin")) {
                    try {
                        L.d("Server doesn't believe we're logged in, so logging in...")
                        CsrfTokenClient.getToken(wiki)
                        shouldRetry = true
                    } catch (caught: Throwable) {
                        errorMsg = caught
                    }
                }
                L.w(errorMsg)
            } finally {
                Prefs.readingListsLastSyncTime = client.lastDateHeader?.toString() ?: lastSyncTime
                Prefs.readingListsDeletedIds = listIdsDeleted
                Prefs.readingListPagesDeletedIds = pageIdsDeleted
                readingListSyncNotification.cancelNotification(applicationContext)
                if (shouldSendSyncEvent) {
                    SavedPageSyncService.sendSyncEvent(extras.containsKey(SYNC_EXTRAS_REFRESHING))
                }
                if ((shouldRetry || shouldRetryWithForce) && !extras.containsKey(SYNC_EXTRAS_RETRYING)) {
                    val b = Bundle()
                    b.putAll(extras)
                    b.putBoolean(SYNC_EXTRAS_RETRYING, true)
                    if (shouldRetryWithForce) {
                        b.putBoolean(SYNC_EXTRAS_FORCE_FULL_SYNC, true)
                    }
                    manualSync(b)
                }
                IN_PROGRESS = false
                SavedPageSyncService.enqueue()
                L.d("Finished sync of reading lists.")
            }
            Result.success()
        }
    }

    private fun getBooleanExtraFromData(inputData: Data): Bundle {
        val extras = Bundle()
        inputData.keyValueMap.forEach {
            extras.putBoolean(it.key, it.value as Boolean)
        }
        return extras
    }

    private fun createOrUpdatePage(listForPage: ReadingList,
                                   remotePage: RemoteReadingListEntry) {
        val remoteTitle = pageTitleFromRemoteEntry(remotePage)
        var localPage = listForPage.pages.find { ReadingListPage.toPageTitle(it) == remoteTitle }
        var updateOnly = localPage != null

        if (localPage == null) {
            localPage = ReadingListPage(pageTitleFromRemoteEntry(remotePage))
            localPage.listId = listForPage.id
            if (AppDatabase.instance.readingListPageDao().pageExistsInList(listForPage, remoteTitle)) {
                updateOnly = true
            }
        }
        localPage.remoteId = remotePage.id
        if (updateOnly) {
            L.d("Updating local page " + localPage.apiTitle)
            AppDatabase.instance.readingListPageDao().updateReadingListPage(localPage)
        } else {
            L.d("Creating local page " + localPage.apiTitle)
            AppDatabase.instance.readingListPageDao().addPagesToList(listForPage, listOf(localPage), false)
        }
    }

    private fun deletePageByTitle(listForPage: ReadingList, title: PageTitle) {
        var localPage = listForPage.pages.find { ReadingListPage.toPageTitle(it) == title }
        if (localPage == null) {
            localPage = AppDatabase.instance.readingListPageDao().getPageByTitle(listForPage, title)
            if (localPage == null) {
                return
            }
        }
        L.d("Deleting local page " + localPage.apiTitle)
        AppDatabase.instance.readingListPageDao().markPagesForDeletion(listForPage, listOf(localPage), false)
    }

    private fun pageTitleFromRemoteEntry(remoteEntry: RemoteReadingListEntry): PageTitle {
        return PageTitle(remoteEntry.title(), WikiSite(remoteEntry.project()))
    }

    private fun remoteEntryFromLocalPage(localPage: ReadingListPage): RemoteReadingListEntry {
        val title = ReadingListPage.toPageTitle(localPage)
        return RemoteReadingListEntry(0, 0,
            "${title.wikiSite.scheme()}://${title.wikiSite.authority()}", title.prefixedText)
    }

    companion object {
        private const val WORK_NAME = "readingListSyncAdapter"
        private const val SYNC_EXTRAS_FORCE_FULL_SYNC = "forceFullSync"
        private const val SYNC_EXTRAS_REFRESHING = "refreshing"
        private const val SYNC_EXTRAS_RETRYING = "retrying"
        private var IN_PROGRESS = false

        fun inProgress(): Boolean {
            return IN_PROGRESS
        }

        fun setSyncEnabledWithSetup() {
            Prefs.isReadingListSyncEnabled = true
            Prefs.isReadingListsRemoteSetupPending = true
            Prefs.isReadingListsRemoteDeletePending = false
            manualSync()
        }

        fun manualSyncWithDeleteList(list: ReadingList) {
            if (list.remoteId <= 0) {
                return
            }
            Prefs.addReadingListsDeletedIds(setOf(list.remoteId))
            manualSync()
        }

        fun createIdsForDeletion(list: ReadingList, pages: Set<ReadingListPage>): Set<String> {
            return if (list.remoteId <= 0) emptySet() else pages.map { it.remoteId }.filter { it > 0 }.map { "${list.remoteId}:$it" }.toSet()
        }

        fun manualSyncWithDeletePages(list: ReadingList, pages: List<ReadingListPage>) {
            val ids = createIdsForDeletion(list, pages.toSet())
            if (ids.isNotEmpty()) {
                Prefs.addReadingListPagesDeletedIds(ids)
                manualSync()
            }
        }

        fun manualSyncWithForce() {
            manualSync(bundleOf(SYNC_EXTRAS_FORCE_FULL_SYNC to true))
        }

        fun manualSyncWithRefresh() {
            Prefs.isSuggestedEditsHighestPriorityEnabled = false
            manualSync(bundleOf(SYNC_EXTRAS_REFRESHING to true))
        }

        fun manualSync(extras: Bundle = Bundle()) {
            if (inProgress()) {
                return
            }
            if (AccountUtil.account() == null || !WikipediaApp.instance.isOnline) {
                if (extras.containsKey(SYNC_EXTRAS_REFRESHING)) {
                    SavedPageSyncService.sendSyncEvent()
                }
                return
            }
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)

            // Convert the Bundle to a Data object
            val dataBuilder = Data.Builder()
            extras.keySet().forEach {
                dataBuilder.putBoolean(it, extras.getBoolean(it))
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val workRequest = OneTimeWorkRequestBuilder<ReadingListSyncAdapter>()
                .setConstraints(constraints)
                .setInputData(dataBuilder.build())
                .build()
            WorkManager.getInstance(WikipediaApp.instance)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest)
        }
    }
}
