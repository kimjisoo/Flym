package net.frju.flym.ui.main

import android.arch.lifecycle.Observer
import android.graphics.Color
import android.os.Bundle
import android.support.v4.app.FragmentTransaction
import android.support.v4.view.GravityCompat
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.text.Html
import android.widget.Toast
import ir.mirrajabi.searchdialog.SimpleSearchDialogCompat
import ir.mirrajabi.searchdialog.core.BaseFilter
import ir.mirrajabi.searchdialog.core.SearchResultListener
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.view_main_containers.*
import net.fred.feedex.R
import net.frju.flym.App
import net.frju.flym.data.entities.EntryWithFeed
import net.frju.flym.data.entities.Feed
import net.frju.flym.data.entities.SearchFeedResult
import net.frju.flym.service.FetcherService
import net.frju.flym.ui.entries.EntriesFragment
import net.frju.flym.ui.entrydetails.EntryDetailsFragment
import okhttp3.Request
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.hintTextColor
import org.jetbrains.anko.notificationManager
import org.jetbrains.anko.sdk21.coroutines.onClick
import org.jetbrains.anko.textColor
import org.json.JSONObject
import java.net.URLEncoder
import java.util.*


class MainActivity : AppCompatActivity(), MainNavigator {

    companion object {

        private const val TAG_DETAILS = "TAG_DETAILS"
        private const val TAG_MASTER = "TAG_MASTER"

        private const val FEED_SEARCH_TITLE = "title"
        private const val FEED_SEARCH_URL = "feedId"
        private const val FEED_SEARCH_DESC = "description"
        private val DEFAULT_FEEDS = arrayListOf(SearchFeedResult("http://www.nytimes.com/services/xml/rss/nyt/World.xml", "NY Times", "Word news"))
    }

    private val feedGroups = mutableListOf<FeedGroup>()
    private val feedAdapter = FeedAdapter(feedGroups)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        nav.layoutManager = LinearLayoutManager(this)
        nav.adapter = feedAdapter
        add_feed_fab.onClick {
            val searchDialog = SimpleSearchDialogCompat(this@MainActivity, "Search...",
                    "What are you looking for...?", null, DEFAULT_FEEDS,
                    SearchResultListener<SearchFeedResult> { dialog, item, position ->
                        Toast.makeText(this@MainActivity, "Added",
                                Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        doAsync { App.db.feedDao().insert(Feed(link = item.link, title = item.name)) }
                    })

            val apiFilter = object : BaseFilter<SearchFeedResult>() {
                override fun performFiltering(charSequence: CharSequence): FilterResults? {
                    doBeforeFiltering()

                    val results = FilterResults()
                    val array = ArrayList<SearchFeedResult>()

                    if (charSequence.isNotEmpty()) {
                        try {
                            val request = Request.Builder()
                                    .url("http://cloud.feedly.com/v3/search/feeds?count=20&locale=" + resources.configuration.locale.language + "&query=" + URLEncoder.encode(charSequence.toString(), "UTF-8"))
                                    .build()
                            FetcherService.HTTP_CLIENT.newCall(request).execute().use {
                                it.body()?.let { body ->
                                    val jsonStr = body.string()

                                    // Parse results
                                    val entries = JSONObject(jsonStr).getJSONArray("results")
                                    for (i in 0 until entries.length()) {
                                        try {
                                            val entry = entries.get(i) as JSONObject
                                            val url = entry.get(FEED_SEARCH_URL).toString().replace("feed/", "")
                                            if (!url.isEmpty()) {
                                                array.add(
                                                        SearchFeedResult(url,
                                                                Html.fromHtml(entry.get(FEED_SEARCH_TITLE).toString()).toString(),
                                                                Html.fromHtml(entry.get(FEED_SEARCH_DESC).toString()).toString()))
                                            }
                                        } catch (ignored: Exception) {
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    } else {
                        array.addAll(DEFAULT_FEEDS)
                    }

                    results.values = array
                    results.count = array.size
                    return results
                }

                override fun publishResults(charSequence: CharSequence, filterResults: FilterResults?) {
                    filterResults?.let {
                        @Suppress("UNCHECKED_CAST")
                        searchDialog.filterResultListener.onFilter(it.values as ArrayList<SearchFeedResult>)
                    }
                    doAfterFiltering()
                }
            }
            searchDialog.filter = apiFilter
            searchDialog.show()
            searchDialog.searchBox.apply {
                textColor = Color.BLACK
                hintTextColor = Color.GRAY
            }

        }

        App.db.feedDao().observeAll.observe(this@MainActivity, Observer {
            it?.let {
                feedGroups.clear()

                val all = Feed()
                all.id = Feed.ALL_ENTRIES_ID
                all.title = getString(R.string.all_entries)
                feedGroups.add(FeedGroup(all, listOf()))

                val subFeedMap = it.groupBy { it.groupId }

                feedGroups.addAll(
                        subFeedMap[null]?.map { FeedGroup(it, subFeedMap[it.id].orEmpty()) }.orEmpty()
                )

                feedAdapter.notifyParentDataSetChanged(true)

                feedAdapter.onFeedClick { view, feed ->
                    goToEntriesList(feed)
                    closeDrawer()
                }
            }
        })

        toolbar.setNavigationIcon(R.drawable.ic_menu_24dp)
        toolbar.setNavigationOnClickListener({ toggleDrawer() })

        if (savedInstanceState == null) {
            closeDrawer()

            goToEntriesList(null)
        }
    }

    override fun onResume() {
        super.onResume()

        notificationManager.cancel(0)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        feedAdapter.onRestoreInstanceState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        feedAdapter.onSaveInstanceState(outState)
    }

    fun closeDrawer() {
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.postDelayed({ drawer.closeDrawer(GravityCompat.START) }, 100)
        }
    }

    fun openDrawer() {
        if (drawer != null && !drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.openDrawer(GravityCompat.START)
        }
    }

    fun toggleDrawer() {
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else if (drawer != null && !drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.openDrawer(GravityCompat.START)
        }
    }

    fun goBack(): Boolean {
        val state = containers_layout.state
        if (state == MainNavigator.State.TWO_COLUMNS_WITH_DETAILS && !containers_layout.hasTwoColumns()) {
            if (clearDetails()) {
                containers_layout.state = MainNavigator.State.TWO_COLUMNS_EMPTY
                return true
            }
        }
        return false
    }

    override fun onBackPressed() {
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else if (!goBack()) {
            super.onBackPressed()
        }
    }

    private fun clearDetails(): Boolean {
        val details = supportFragmentManager.findFragmentByTag(TAG_DETAILS)
        if (details != null) {
            supportFragmentManager
                    .beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    .remove(details)
                    .commit()
            return true
        }
        return false
    }

    override fun goToEntriesList(feed: Feed?) {
        clearDetails()
        containers_layout.state = MainNavigator.State.TWO_COLUMNS_EMPTY

        // We try to reuse the fragment to avoid loosing the bottom tab position
        val currentFragment = supportFragmentManager.findFragmentById(R.id.frame_master)
        if (currentFragment is EntriesFragment) {
            currentFragment.feed = feed
        } else {
            val master = EntriesFragment.newInstance(feed)
            supportFragmentManager.beginTransaction().replace(R.id.frame_master, master, TAG_MASTER).commit()
        }
    }

    override fun goToEntryDetails(entry: EntryWithFeed, allEntryIds: List<String>) {
        containers_layout.state = MainNavigator.State.TWO_COLUMNS_WITH_DETAILS
        val fragment = EntryDetailsFragment.newInstance(entry, allEntryIds)
        supportFragmentManager
                .beginTransaction()
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                .replace(R.id.frame_details, fragment, TAG_DETAILS)
                .commit()

        val listFragment = supportFragmentManager.findFragmentById(R.id.frame_master) as EntriesFragment
        listFragment.setSelectedEntryId(entry.id)
    }

    override fun setSelectedEntryId(selectedEntryId: String) {
        val listFragment = supportFragmentManager.findFragmentById(R.id.frame_master) as EntriesFragment
        listFragment.setSelectedEntryId(selectedEntryId)
    }

    override fun goToSettings() {
        //start new activity
    }

    override fun goToFeedback() {
        //start new activity
    }
}
