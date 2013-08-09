package de.danoeh.antennapod.storage;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Handler;
import android.util.Log;
import de.danoeh.antennapod.AppConfig;
import de.danoeh.antennapod.feed.EventDistributor;
import de.danoeh.antennapod.feed.Feed;
import de.danoeh.antennapod.feed.FeedImage;
import de.danoeh.antennapod.feed.FeedItem;
import de.danoeh.antennapod.feed.FeedMedia;
import de.danoeh.antennapod.preferences.UserPreferences;
import de.danoeh.antennapod.service.PlaybackService;
import de.danoeh.antennapod.service.download.DownloadStatus;
import de.danoeh.antennapod.util.DownloadError;
import de.danoeh.antennapod.util.NetworkUtils;
import de.danoeh.antennapod.util.QueueAccess;
import de.danoeh.antennapod.util.exception.MediaFileNotFoundException;

public final class DBTasks {
    private static final String TAG = "DBTasks";

    private DBTasks() {
    }

    public static void playMedia(final Context context, final FeedMedia media,
                                 boolean showPlayer, boolean startWhenPrepared, boolean shouldStream) {
        try {
            if (!shouldStream) {
                if (media.fileExists() == false) {
                    throw new MediaFileNotFoundException(
                            "No episode was found at " + media.getFile_url(),
                            media);
                }
            }
            // Start playback Service
            Intent launchIntent = new Intent(context, PlaybackService.class);
            launchIntent.putExtra(PlaybackService.EXTRA_PLAYABLE, media);
            launchIntent.putExtra(PlaybackService.EXTRA_START_WHEN_PREPARED,
                    startWhenPrepared);
            launchIntent.putExtra(PlaybackService.EXTRA_SHOULD_STREAM,
                    shouldStream);
            launchIntent.putExtra(PlaybackService.EXTRA_PREPARE_IMMEDIATELY,
                    true);
            context.startService(launchIntent);
            if (showPlayer) {
                // Launch Mediaplayer
                context.startActivity(PlaybackService.getPlayerActivityIntent(
                        context, media));
            }
            DBWriter.addQueueItemAt(context, media.getItem().getId(), 0, false);
        } catch (MediaFileNotFoundException e) {
            e.printStackTrace();
            if (media.isPlaying()) {
                context.sendBroadcast(new Intent(
                        PlaybackService.ACTION_SHUTDOWN_PLAYBACK_SERVICE));
            }
            notifyMissingFeedMediaFile(context, media);
        }
    }

    private static AtomicBoolean isRefreshing = new AtomicBoolean(false);
    public static void refreshAllFeeds(final Context context,
                                       final List<Feed> feeds) {
        if (isRefreshing.compareAndSet(false, true)) {
            new Thread() {
                public void run() {
                    if (feeds != null) {
                        refreshFeeds(context, feeds);
                    } else {
                        refreshFeeds(context, DBReader.getFeedList(context));
                    }
                    isRefreshing.set(false);
                }
            }.start();
        } else {
            if (AppConfig.DEBUG)
                Log.d(TAG,
                        "Ignoring request to refresh all feeds: Refresh lock is locked");
        }
    }

    public static void refreshExpiredFeeds(final Context context) {
        if (AppConfig.DEBUG)
            Log.d(TAG, "Refreshing expired feeds");

        new Thread() {
            public void run() {
                long millis = UserPreferences.getUpdateInterval();

                if (millis > 0) {
                    long now = Calendar.getInstance().getTime().getTime();

                    // Allow a 10 minute window
                    millis -= 10 * 60 * 1000;
                    List<Feed> feedList = DBReader.getExpiredFeedsList(context,
                            now - millis);
                    if (feedList.size() > 0) {
                        refreshFeeds(context, feedList);
                    }
                }
            }
        }.start();
    }

    private static void refreshFeeds(final Context context,
                                     final List<Feed> feedList) {

        for (Feed feed : feedList) {
            try {
                refreshFeed(context, feed);
            } catch (DownloadRequestException e) {
                e.printStackTrace();
                DBWriter.addDownloadStatus(
                        context,
                        new DownloadStatus(feed, feed
                                .getHumanReadableIdentifier(),
                                DownloadError.ERROR_REQUEST_ERROR, false, e
                                .getMessage()));
            }
        }

    }

    /**
     * Updates a specific feed.
     */
    public static void refreshFeed(Context context, Feed feed)
            throws DownloadRequestException {
        DownloadRequester.getInstance().downloadFeed(context,
                new Feed(feed.getDownload_url(), new Date(), feed.getTitle()));
    }

    public static void notifyInvalidImageFile(final Context context,
                                              final FeedImage image) {
        Log.i(TAG,
                "The feedmanager was notified about an invalid image download. It will now try to redownload the image file");
        try {
            DownloadRequester.getInstance().downloadImage(context, image);
        } catch (DownloadRequestException e) {
            e.printStackTrace();
            Log.w(TAG, "Failed to download invalid feed image");
        }
    }

    public static void notifyMissingFeedMediaFile(final Context context,
                                                  final FeedMedia media) {
        Log.i(TAG,
                "The feedmanager was notified about a missing episode. It will update its database now.");
        media.setDownloaded(false);
        media.setFile_url(null);
        DBWriter.setFeedMedia(context, media);
        EventDistributor.getInstance().sendFeedUpdateBroadcast();
    }

    public static void downloadAllItemsInQueue(final Context context) {
        new Thread() {
            public void run() {
                List<FeedItem> queue = DBReader.getQueue(context);
                if (!queue.isEmpty()) {
                    try {
                        downloadFeedItems(context,
                                queue.toArray(new FeedItem[queue.size()]));
                    } catch (DownloadRequestException e) {
                        e.printStackTrace();
                    }
                }
            }
        }.start();
    }

    public static void downloadFeedItems(final Context context,
                                         FeedItem... items) throws DownloadRequestException {
        downloadFeedItems(true, context, items);
    }

    private static void downloadFeedItems(boolean performAutoCleanup,
                                          final Context context, final FeedItem... items)
            throws DownloadRequestException {
        final DownloadRequester requester = DownloadRequester.getInstance();

        if (performAutoCleanup) {
            new Thread() {

                @Override
                public void run() {
                    performAutoCleanup(context,
                            getPerformAutoCleanupArgs(context, items.length));
                }

            }.start();
        }
        for (FeedItem item : items) {
            if (item.getMedia() != null
                    && !requester.isDownloadingFile(item.getMedia())
                    && !item.getMedia().isDownloaded()) {
                if (items.length > 1) {
                    try {
                        requester.downloadMedia(context, item.getMedia());
                    } catch (DownloadRequestException e) {
                        e.printStackTrace();
                        DBWriter.addDownloadStatus(context,
                                new DownloadStatus(item.getMedia(), item
                                        .getMedia()
                                        .getHumanReadableIdentifier(),
                                        DownloadError.ERROR_REQUEST_ERROR,
                                        false, e.getMessage()));
                    }
                } else {
                    requester.downloadMedia(context, item.getMedia());
                }
            }
        }
    }

    private static int getNumberOfUndownloadedEpisodes(
            final List<FeedItem> queue, final List<FeedItem> unreadItems) {
        int counter = 0;
        for (FeedItem item : queue) {
            if (item.hasMedia() && !item.getMedia().isDownloaded()
                    && !item.getMedia().isPlaying()) {
                counter++;
            }
        }
        for (FeedItem item : unreadItems) {
            if (item.hasMedia() && !item.getMedia().isDownloaded()) {
                counter++;
            }
        }
        return counter;
    }

    public static void autodownloadUndownloadedItems(final Context context) {
        if (AppConfig.DEBUG)
            Log.d(TAG, "Performing auto-dl of undownloaded episodes");
        if (NetworkUtils.autodownloadNetworkAvailable(context)
                && UserPreferences.isEnableAutodownload()) {
            final List<FeedItem> queue = DBReader.getQueue(context);
            final List<FeedItem> unreadItems = DBReader
                    .getUnreadItemsList(context);

            int undownloadedEpisodes = getNumberOfUndownloadedEpisodes(queue,
                    unreadItems);
            int downloadedEpisodes = DBReader
                    .getNumberOfDownloadedEpisodes(context);
            int deletedEpisodes = performAutoCleanup(context,
                    getPerformAutoCleanupArgs(context, undownloadedEpisodes));
            int episodeSpaceLeft = undownloadedEpisodes;
            boolean cacheIsUnlimited = UserPreferences.getEpisodeCacheSize() == UserPreferences
                    .getEpisodeCacheSizeUnlimited();

            if (!cacheIsUnlimited
                    && UserPreferences.getEpisodeCacheSize() < downloadedEpisodes
                    + undownloadedEpisodes) {
                episodeSpaceLeft = UserPreferences.getEpisodeCacheSize()
                        - (downloadedEpisodes - deletedEpisodes);
            }

            List<FeedItem> itemsToDownload = new ArrayList<FeedItem>();
            if (episodeSpaceLeft > 0 && undownloadedEpisodes > 0) {
                for (int i = 0; i < queue.size(); i++) { // ignore playing item
                    FeedItem item = queue.get(i);
                    if (item.hasMedia() && !item.getMedia().isDownloaded()
                            && !item.getMedia().isPlaying()) {
                        itemsToDownload.add(item);
                        episodeSpaceLeft--;
                        undownloadedEpisodes--;
                        if (episodeSpaceLeft == 0 || undownloadedEpisodes == 0) {
                            break;
                        }
                    }
                }
            }
            if (episodeSpaceLeft > 0 && undownloadedEpisodes > 0) {
                for (FeedItem item : unreadItems) {
                    if (item.hasMedia() && !item.getMedia().isDownloaded()) {
                        itemsToDownload.add(item);
                        episodeSpaceLeft--;
                        undownloadedEpisodes--;
                        if (episodeSpaceLeft == 0 || undownloadedEpisodes == 0) {
                            break;
                        }
                    }
                }
            }
            if (AppConfig.DEBUG)
                Log.d(TAG, "Enqueueing " + itemsToDownload.size()
                        + " items for download");

            try {
                downloadFeedItems(false, context,
                        itemsToDownload.toArray(new FeedItem[itemsToDownload
                                .size()]));
            } catch (DownloadRequestException e) {
                e.printStackTrace();
            }

        }
    }

    private static int getPerformAutoCleanupArgs(Context context,
                                                 final int episodeNumber) {
        if (episodeNumber >= 0
                && UserPreferences.getEpisodeCacheSize() != UserPreferences
                .getEpisodeCacheSizeUnlimited()) {
            int downloadedEpisodes = DBReader
                    .getNumberOfDownloadedEpisodes(context);
            if (downloadedEpisodes + episodeNumber >= UserPreferences
                    .getEpisodeCacheSize()) {

                return downloadedEpisodes + episodeNumber
                        - UserPreferences.getEpisodeCacheSize();
            }
        }
        return 0;
    }

    public static void performAutoCleanup(final Context context) {
        performAutoCleanup(context, getPerformAutoCleanupArgs(context, 0));
    }

    private static int performAutoCleanup(final Context context,
                                          final int episodeNumber) {
        List<FeedItem> candidates = DBReader.getDownloadedItems(context);
        List<FeedItem> queue = DBReader.getQueue(context);
        List<FeedItem> delete;
        for (FeedItem item : candidates) {
            if (item.hasMedia() && item.getMedia().isDownloaded()
                    && !queue.contains(item) && item.isRead()) {
                candidates.add(item);
            }

        }

        Collections.sort(candidates, new Comparator<FeedItem>() {
            @Override
            public int compare(FeedItem lhs, FeedItem rhs) {
                Date l = lhs.getMedia().getPlaybackCompletionDate();
                Date r = rhs.getMedia().getPlaybackCompletionDate();

                if (l == null) {
                    l = new Date(0);
                }
                if (r == null) {
                    r = new Date(0);
                }
                return l.compareTo(r);
            }
        });

        if (candidates.size() > episodeNumber) {
            delete = candidates.subList(0, episodeNumber);
        } else {
            delete = candidates;
        }

        for (FeedItem item : delete) {
            DBWriter.deleteFeedMediaOfItem(context, item.getId());
        }

        int counter = delete.size();

        if (AppConfig.DEBUG)
            Log.d(TAG, String.format(
                    "Auto-delete deleted %d episodes (%d requested)", counter,
                    episodeNumber));

        return counter;
    }

    public static void enqueueAllNewItems(final Context context) {
        long[] unreadItems = DBReader.getUnreadItemIds(context);
        DBWriter.addQueueItem(context, unreadItems);
    }

    public static FeedItem getQueueSuccessorOfItem(Context context,
                                                   final long itemId, List<FeedItem> queue) {
        FeedItem result = null;
        if (queue == null) {
            queue = DBReader.getQueue(context);
        }
        if (queue != null) {
            Iterator<FeedItem> iterator = queue.iterator();
            while (iterator.hasNext()) {
                FeedItem item = iterator.next();
                if (item.getId() == itemId) {
                    if (iterator.hasNext()) {
                        result = iterator.next();
                    }
                    break;
                }
            }
        }
        return result;
    }

    public static boolean isInQueue(Context context, final long feedItemId) {
        List<Long> queue = DBReader.getQueueIDList(context);
        return QueueAccess.IDListAccess(queue).contains(feedItemId);
    }

    private static Feed searchFeedByIdentifyingValue(Context context,
                                                     String identifier) {
        List<Feed> feeds = DBReader.getFeedList(context);
        for (Feed feed : feeds) {
            if (feed.getIdentifyingValue().equals(identifier)) {
                return feed;
            }
        }
        return null;
    }

    /**
     * Get a FeedItem by its identifying value.
     */
    private static FeedItem searchFeedItemByIdentifyingValue(Feed feed,
                                                             String identifier) {
        for (FeedItem item : feed.getItems()) {
            if (item.getIdentifyingValue().equals(identifier)) {
                return item;
            }
        }
        return null;
    }

    public static synchronized Feed updateFeed(final Context context,
                                               final Feed newFeed) {
        // Look up feed in the feedslist
        final Feed savedFeed = searchFeedByIdentifyingValue(context,
                newFeed.getIdentifyingValue());
        if (savedFeed == null) {
            if (AppConfig.DEBUG)
                Log.d(TAG,
                        "Found no existing Feed with title "
                                + newFeed.getTitle() + ". Adding as new one.");
            // Add a new Feed
            try {
                DBWriter.addNewFeed(context, newFeed).get();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
            return newFeed;
        } else {
            if (AppConfig.DEBUG)
                Log.d(TAG, "Feed with title " + newFeed.getTitle()
                        + " already exists. Syncing new with existing one.");

            savedFeed.setItems(DBReader.getFeedItemList(context, savedFeed));
            if (savedFeed.compareWithOther(newFeed)) {
                if (AppConfig.DEBUG)
                    Log.d(TAG,
                            "Feed has updated attribute values. Updating old feed's attributes");
                savedFeed.updateFromOther(newFeed);
            }
            // Look for new or updated Items
            for (int idx = 0; idx < newFeed.getItems().size(); idx++) {
                final FeedItem item = newFeed.getItems().get(idx);
                FeedItem oldItem = searchFeedItemByIdentifyingValue(savedFeed,
                        item.getIdentifyingValue());
                if (oldItem == null) {
                    // item is new
                    final int i = idx;
                    item.setFeed(savedFeed);
                    savedFeed.getItems().add(i, item);
                    DBWriter.markItemRead(context, item.getId(), false);
                } else {
                    oldItem.updateFromOther(item);
                }
            }
            // update attributes
            savedFeed.setLastUpdate(newFeed.getLastUpdate());
            savedFeed.setType(newFeed.getType());
            try {
                DBWriter.setCompleteFeed(context, savedFeed).get();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
            new Thread() {
                @Override
                public void run() {
                    autodownloadUndownloadedItems(context);
                }
            }.start();
            return savedFeed;
        }
    }

    /**
     * Searches the titles of FeedItems of a specific feed for a given
     * string.
     *
     * @param feedID  The id of the feed whose items should be searched.
     * @param query The search string
     */
    public static FutureTask<List<FeedItem>> searchFeedItemTitle(final Context context,
                                                                 final long feedID, final String query) {
        return new FutureTask<List<FeedItem>>(new QueryTask<List<FeedItem>>(context) {
            @Override
            public void execute(PodDBAdapter adapter) {
                Cursor searchResult = adapter.searchItemTitles(feedID,
                        query);
                List<FeedItem> items = DBReader.extractItemlistFromCursor(context, searchResult);
                DBReader.loadFeedDataOfFeedItemlist(context, items);
                setResult(items);
                searchResult.close();
            }
        });
    }

    /**
     * Searches the descriptions of FeedItems of a specific feed for a given
     * string.
     *
     * @param feedID The id of the feed whose items should be searched.
     * @param query The search string
     */
    public static FutureTask<List<FeedItem>> searchFeedItemDescription(final Context context,
                                                                       final long feedID, final String query) {
        return new FutureTask<List<FeedItem>>(new QueryTask<List<FeedItem>>(context) {
            @Override
            public void execute(PodDBAdapter adapter) {
                Cursor searchResult = adapter.searchItemDescriptions(feedID,
                        query);
                List<FeedItem> items = DBReader.extractItemlistFromCursor(context, searchResult);
                DBReader.loadFeedDataOfFeedItemlist(context, items);
                setResult(items);
                searchResult.close();
            }
        });
    }

    /**
     * Searches the 'contentEncoded' field of FeedItems of a specific feed for a
     * given string.
     *
     * @param feedID  The id of the feed whose items should be searched.
     * @param query The search string
     */
    public static FutureTask<List<FeedItem>> searchFeedItemContentEncoded(final Context context,
                                                                          final long feedID, final String query) {
        return new FutureTask<List<FeedItem>>(new QueryTask<List<FeedItem>>(context) {
            @Override
            public void execute(PodDBAdapter adapter) {
                Cursor searchResult = adapter.searchItemContentEncoded(feedID,
                        query);
                List<FeedItem> items = DBReader.extractItemlistFromCursor(context, searchResult);
                DBReader.loadFeedDataOfFeedItemlist(context, items);
                setResult(items);
                searchResult.close();
            }
        });
    }

    /**
     * Searches chapters for a given string.
     *
     * @param feedID  The id of the feed whose items should be searched.
     * @param query The search string
     */
    public static FutureTask<List<FeedItem>> searchFeedItemChapters(final Context context,
                                                                    final long feedID, final String query) {
        return new FutureTask<List<FeedItem>>(new QueryTask<List<FeedItem>>(context) {
            @Override
            public void execute(PodDBAdapter adapter) {
                Cursor searchResult = adapter.searchItemChapters(feedID,
                        query);
                List<FeedItem> items = DBReader.extractItemlistFromCursor(context, searchResult);
                DBReader.loadFeedDataOfFeedItemlist(context, items);
                setResult(items);
                searchResult.close();
            }
        });
    }

    /**
     * A runnable which should be used for database queries. The onCompletion
     * method is executed on the database executor to handle Cursors correctly.
     * This class automatically creates a PodDBAdapter object and closes it when
     * it is no longer in use.
     */
    static abstract class QueryTask<T> implements Callable<T> {
        private T result;
        private Context context;

        public QueryTask(Context context) {
            this.context = context;
        }

        @Override
        public T call() throws Exception {
            PodDBAdapter adapter = new PodDBAdapter(context);
            adapter.open();
            execute(adapter);
            adapter.close();
            return result;
        }

        public abstract void execute(PodDBAdapter adapter);

        protected void setResult(T result) {
            this.result = result;
        }
    }

}