package com.gozar

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppPickerTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun clean() {
        context.getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun sheetUsesNestedRecyclerScrollingAndSearchesSystemAndInstalledApps() {
        val controller = Robolectric.buildActivity(LoginActivity::class.java).setup()
        val activity = controller.get()
        val picker = AppPicker(activity) {}
        val sheet = picker.show(listOf(
            AppPicker.Row("example.alpha", "Alpha", null, false),
            AppPicker.Row("system.beta", "Beta", null, true)
        ))
        shadowOf(Looper.getMainLooper()).idle()
        val list = sheet.findViewById<RecyclerView>(R.id.appList)!!
        val search = sheet.findViewById<EditText>(R.id.appSearch)!!
        assertTrue(list.isNestedScrollingEnabled)
        assertFalse(sheet.behavior.isDraggable)
        assertEquals(2, list.adapter?.itemCount)
        search.setText("beta")
        assertEquals(1, list.adapter?.itemCount)
        search.setText("example.alpha")
        assertEquals(1, list.adapter?.itemCount)
        search.setText("not found")
        assertEquals(0, list.adapter?.itemCount)
        assertEquals(View.VISIBLE, sheet.findViewById<View>(R.id.appEmpty)?.visibility)
        sheet.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun allowAndDisallowModesAreNeverMixedAndEmptyValidSelectionFails() {
        val calls = mutableListOf<String>()
        assertTrue(FoxVpnService.applyAppSelection(emptySet(), "self", { calls.add("allow:$it") }, { calls.add("deny:$it") }))
        assertEquals(listOf("deny:self"), calls)
        calls.clear()
        assertTrue(FoxVpnService.applyAppSelection(setOf("app.one", "self"), "self", { calls.add("allow:$it") }, { calls.add("deny:$it") }))
        assertEquals(listOf("allow:app.one"), calls)
        calls.clear()
        assertFalse(FoxVpnService.applyAppSelection(setOf("gone"), "self", { throw IllegalArgumentException() }, { calls.add("deny:$it") }))
        assertTrue(calls.isEmpty())
    }
}
