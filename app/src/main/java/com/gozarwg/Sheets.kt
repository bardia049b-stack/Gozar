package com.gozarwg

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
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
        return open(context, title, actions)
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
        return open(context, R.string.add_title, items.mapIndexed { index, item ->
            SheetAction(item.first, item.second) { onPick(keys[index]) }
        })
    }

    /** Uses labelled Material icons in a compact action grid rather than a sharp-cornered list. */
    private fun open(context: Context, title: Int, actions: List<SheetAction>): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_actions, null)
        dialog.setContentView(view)
        view.findViewById<TextView>(R.id.tvSheetTitle).setText(title)
        view.findViewById<View>(R.id.btnSheetClose).setOnClickListener { dialog.dismiss() }
        val group = view.findViewById<GridLayout>(R.id.llActions)
        group.columnCount = if (actions.size <= 4) actions.size.coerceAtLeast(1) else 3
        actions.forEach { action -> group.addView(tile(context, group, action) { dialog.dismiss() }) }
        dialog.show()
        return dialog
    }

    private fun tile(
        context: Context,
        parent: GridLayout,
        action: SheetAction,
        dismiss: () -> Unit
    ): View {
        val tile = LayoutInflater.from(context).inflate(R.layout.item_action, parent, false)
        val icon = tile.findViewById<ImageView>(R.id.actionIcon)
        val label = tile.findViewById<TextView>(R.id.actionLabel)
        icon.setImageResource(action.icon)
        if (action.tone == R.color.m3_error) {
            icon.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.m3_error_container))
            icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.m3_on_error_container))
            label.setTextColor(ContextCompat.getColor(context, R.color.m3_error))
        } else if (action.tone != 0) {
            icon.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, action.tone))
            icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.m3_on_primary))
            label.setTextColor(ContextCompat.getColor(context, action.tone))
        }
        label.setText(action.label)
        val params = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            setGravity(Gravity.FILL_HORIZONTAL)
            val margin = (4 * context.resources.displayMetrics.density + 0.5f).toInt()
            setMargins(margin, 0, margin, 0)
        }
        tile.layoutParams = params
        tile.setOnClickListener {
            dismiss()
            action.run()
        }
        return tile
    }
}
