package com.gozarwg

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

class SheetAction(
    val icon: Int,
    val label: Int,
    val tone: Int = 0,
    val run: () -> Unit
)

object Sheets {

    fun actions(context: Context, title: Int, actions: List<SheetAction>): BottomSheetDialog {
        return open(context, title, actions.map { it })
    }

    fun add(context: Context, onPick: (String) -> Unit): BottomSheetDialog {
        val keys = listOf("file", "qr", "gallery", "paste", "link", "create")
        val items = listOf(
            R.drawable.ic_folder to R.string.imp_file,
            R.drawable.ic_qr to R.string.imp_qr,
            R.drawable.ic_image to R.string.imp_gallery,
            R.drawable.ic_paste to R.string.imp_paste,
            R.drawable.ic_link to R.string.imp_link,
            R.drawable.ic_edit to R.string.imp_create
        )
        val actions = items.mapIndexed { index, item ->
            SheetAction(item.first, item.second) { onPick(keys[index]) }
        }
        return open(context, R.string.add_title, actions)
    }

    private fun open(context: Context, title: Int, actions: List<SheetAction>): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_actions, null)
        dialog.setContentView(view)
        view.findViewById<TextView>(R.id.tvSheetTitle).setText(title)
        val group = view.findViewById<LinearLayout>(R.id.llActions)
        actions.forEachIndexed { index, action ->
            group.addView(row(context, group, action, index, actions.size) { dialog.dismiss() })
        }
        dialog.show()
        return dialog
    }

    private fun row(
        context: Context,
        parent: LinearLayout,
        action: SheetAction,
        index: Int,
        total: Int,
        dismiss: () -> Unit
    ): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_action, parent, false)
        row.setBackgroundResource(shapeFor(index, total))
        val icon = row.findViewById<ImageView>(R.id.actionIcon)
        icon.setImageResource(action.icon)
        if (action.tone != 0) {
            icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, action.tone))
        }
        row.findViewById<TextView>(R.id.actionLabel).setText(action.label)
        row.setOnClickListener {
            dismiss()
            action.run()
        }
        return row
    }

    private fun shapeFor(index: Int, total: Int): Int {
        if (total <= 1) return R.drawable.menu_item_only
        return when (index) {
            0 -> R.drawable.menu_item_top
            total - 1 -> R.drawable.menu_item_bottom
            else -> R.drawable.menu_item_mid
        }
    }
}
