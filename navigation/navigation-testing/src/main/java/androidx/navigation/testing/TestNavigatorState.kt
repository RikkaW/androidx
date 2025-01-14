/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.navigation.testing

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.navigation.FloatingWindow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavViewModelStoreProvider
import androidx.navigation.NavigatorState
import androidx.navigation.NavigatorState.OnTransitionCompleteListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * An implementation of [NavigatorState] that allows testing a
 * [androidx.navigation.Navigator] in isolation (i.e., without requiring a
 * [androidx.navigation.NavController]).
 *
 * An optional [context] can be provided to allow for the usages of
 * [androidx.lifecycle.AndroidViewModel] within the created [NavBackStackEntry]
 * instances.
 *
 * The [Lifecycle] of all [NavBackStackEntry] instances added to this TestNavigatorState
 * will be updated as they are added and removed from the state. This work is kicked off
 * on the [coroutineDispatcher].
 */
public class TestNavigatorState @JvmOverloads constructor(
    private val context: Context? = null,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : NavigatorState() {

    private val lifecycleOwner: LifecycleOwner = TestLifecycleOwner(
        Lifecycle.State.RESUMED,
        coroutineDispatcher
    )

    private val viewModelStoreProvider = object : NavViewModelStoreProvider {
        private val viewModelStores = mutableMapOf<String, ViewModelStore>()
        override fun getViewModelStore(
            backStackEntryId: String
        ) = viewModelStores.getOrPut(backStackEntryId) {
            ViewModelStore()
        }
    }

    private val savedStates = mutableMapOf<String, Bundle>()

    override fun createBackStackEntry(
        destination: NavDestination,
        arguments: Bundle?
    ): NavBackStackEntry = NavBackStackEntry.create(
        context, destination, arguments, lifecycleOwner, viewModelStoreProvider
    )

    /**
     * Restore a previously saved [NavBackStackEntry]. You must have previously called
     * [pop] with [previouslySavedEntry] and `true`.
     */
    public fun restoreBackStackEntry(previouslySavedEntry: NavBackStackEntry): NavBackStackEntry {
        val savedState = checkNotNull(savedStates[previouslySavedEntry.id]) {
            "restoreBackStackEntry(previouslySavedEntry) must be passed a NavBackStackEntry " +
                "that was previously popped with popBackStack(previouslySavedEntry, true)"
        }
        return NavBackStackEntry.create(
            context,
            previouslySavedEntry.destination, previouslySavedEntry.arguments,
            lifecycleOwner, viewModelStoreProvider,
            previouslySavedEntry.id, savedState
        )
    }

    override fun push(backStackEntry: NavBackStackEntry) {
        super.push(backStackEntry)
        updateMaxLifecycle()
    }

    override fun pushWithTransition(
        backStackEntry: NavBackStackEntry
    ): OnTransitionCompleteListener {
        val innerListener = super.pushWithTransition(backStackEntry)
        val listener = OnTransitionCompleteListener {
            innerListener.onTransitionComplete()
            updateMaxLifecycle()
        }
        addInProgressTransition(backStackEntry, listener)
        updateMaxLifecycle()
        return listener
    }

    override fun pop(popUpTo: NavBackStackEntry, saveState: Boolean) {
        val beforePopList = backStack.value
        val poppedList = beforePopList.subList(beforePopList.indexOf(popUpTo), beforePopList.size)
        super.pop(popUpTo, saveState)
        updateMaxLifecycle(poppedList, saveState)
    }

    override fun popWithTransition(
        popUpTo: NavBackStackEntry,
        saveState: Boolean
    ): OnTransitionCompleteListener {
        // Get the entry that will be incoming after we have popped all the way up to the desired
        // entry.
        // We need to do this before we call popWithTransition because for the TestNavigatorState
        // pop is called immediately, which would cause the entry to immediately go to RESUMED.
        val incomingEntry = backStack.value.lastOrNull { entry ->
            entry != popUpTo &&
                backStack.value.lastIndexOf(entry) < backStack.value.lastIndexOf(popUpTo)
        }
        // When popping, we need to mark the incoming entry as transitioning so we keep it
        // STARTED until the transition completes at which point we can move it to RESUMED
        if (incomingEntry != null) {
            addInProgressTransition(incomingEntry) {
                removeInProgressTransition(incomingEntry)
                updateMaxLifecycle()
            }
        }
        addInProgressTransition(popUpTo) { }
        val innerListener = super.popWithTransition(popUpTo, saveState)
        val listener = OnTransitionCompleteListener {
            innerListener.onTransitionComplete()
            updateMaxLifecycle(listOf(popUpTo))
        }
        addInProgressTransition(popUpTo, listener)
        updateMaxLifecycle()
        return listener
    }

    private fun updateMaxLifecycle(
        poppedList: List<NavBackStackEntry> = emptyList(),
        saveState: Boolean = false
    ) {
        runBlocking(coroutineDispatcher) {
            // NavBackStackEntry Lifecycles must be updated on the main thread
            // as per the contract within Lifecycle, so we explicitly swap to the main thread
            // no matter what CoroutineDispatcher was passed to us.
            withContext(Dispatchers.Main.immediate) {
                // Mark all removed NavBackStackEntries as DESTROYED
                for (entry in poppedList.reversed()) {
                    if (saveState) {
                        // Move the NavBackStackEntry to the stopped state, then save its state
                        entry.maxLifecycle = Lifecycle.State.CREATED
                        val savedState = Bundle()
                        entry.saveState(savedState)
                        savedStates[entry.id] = savedState
                    }
                    val transitioning = transitionsInProgress.value.containsKey(entry)
                    if (!transitioning) {
                        entry.maxLifecycle = Lifecycle.State.DESTROYED
                    } else {
                        entry.maxLifecycle = Lifecycle.State.CREATED
                    }
                }
                // Now go through the current list of destinations, updating their Lifecycle state
                val currentList = backStack.value
                var previousEntry: NavBackStackEntry? = null
                for (entry in currentList.reversed()) {
                    val transitioning = transitionsInProgress.value.containsKey(entry)
                    entry.maxLifecycle = when {
                        previousEntry == null ->
                            if (!transitioning) {
                                Lifecycle.State.RESUMED
                            } else {
                                Lifecycle.State.STARTED
                            }
                        previousEntry.destination is FloatingWindow -> Lifecycle.State.STARTED
                        else -> Lifecycle.State.CREATED
                    }
                    previousEntry = entry
                }
            }
        }
    }
}
