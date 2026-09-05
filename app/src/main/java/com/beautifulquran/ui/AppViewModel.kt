package com.beautifulquran.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.beautifulquran.QuranApp
import com.beautifulquran.ornamentslab.OrnamentsLabViewModel
import com.beautifulquran.ui.bookmarks.BookmarksViewModel
import com.beautifulquran.ui.home.HomeViewModel
import com.beautifulquran.ui.reader.ReaderViewModel
import com.beautifulquran.ui.rootviewer.RootViewerViewModel
import com.beautifulquran.ui.settings.SettingsViewModel
import com.beautifulquran.ui.share.ShareViewModel
import com.beautifulquran.timingslab.TimingsLabViewModel
import com.beautifulquran.tarjilab.TarjiLabViewModel

/** Tiny hand-rolled DI: ViewModel factory backed by the Application singletons. */
object AppViewModelFactory : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as QuranApp
        return when {
            modelClass.isAssignableFrom(BookmarksViewModel::class.java) ->
                BookmarksViewModel(app.repository, app.bookmarks, app.annotations, app.settings) as T
            modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                HomeViewModel(app.repository, app.settings, app.player) as T
            modelClass.isAssignableFrom(ReaderViewModel::class.java) ->
                ReaderViewModel(
                    app.repository,
                    app.settings,
                    app.bookmarks,
                    app.player,
                    app.annotations,
                    app.outputLatency,
                    app.englishBookCache,
                ) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                SettingsViewModel(app.repository, app.settings, app.tarjiProfiles) as T
            modelClass.isAssignableFrom(TimingsLabViewModel::class.java) ->
                TimingsLabViewModel(app.repository, app.settings, app.player, app.timingOverrides) as T
            modelClass.isAssignableFrom(TarjiLabViewModel::class.java) ->
                TarjiLabViewModel(app.repository, app.settings, app.player, app.tarjiProfiles) as T
            modelClass.isAssignableFrom(RootViewerViewModel::class.java) ->
                RootViewerViewModel(
                    app.repository,
                    app.lexicon,
                    app.dictionary,
                    app.settings,
                    app.player,
                ) as T
            modelClass.isAssignableFrom(ShareViewModel::class.java) ->
                ShareViewModel(app.repository, app.player) as T
            modelClass.isAssignableFrom(OrnamentsLabViewModel::class.java) ->
                OrnamentsLabViewModel(app.ornamentSeeds) as T
            else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
        }
    }
}
