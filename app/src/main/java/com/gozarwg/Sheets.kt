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

enum class Tone(val container: Int, val content: Int) {
    NEUTRAL(R.color.m3_secondary_container, R.color.m3_on_secondary_container),
    PRIMARY(R.color.m3_primary_container, R.color.m3_on_primary_container),
    OK(R.color.m3_ok_container, R.color.m3_on_ok_container),
    WARN(R.color.m3_warn_container, R.color.m3_on_warn_container),
    INFO(R.color.m3_info_container, R.color.m3_on_info_container),
    ERROR(R.color.m3_error_container, R.color.m3_on_error_container)
}

class SheetAction(
    val icon: Int,
    val label: Int,
    val tone: Tone = Tone.NEUTRAL,
    val run: () -> Unit
)

object Sheets {

    fun actions(context: Context, title: Int, actions: List<SheetAction>): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_actions, null)
        dialog.setContentView(view)
        view.findViewById<TextView>(R.id.tvSheetTitle).setText(title)
        val box = view.findViewById<LinearLayout>(R.id.llActions)
        for (action in actions) {
            box.addView(actionRow(context, box, action) { dialog.dismiss() })
        }
        dialog.show()
        return dialog
    }

    fun add(context: Context, onPick: (String) -> Unit): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_actions, null)
        dialog.setContentView(view)
        view.findViewById<TextView>(R.id.tvSheetTitle).setText(R.string.add_title)
        val box = view.findViewById<LinearLayout>(R.id.llActions)
        val items = listOf(
            Triple(R.drawable.ic_folder, R.string.imp_file, Tone.INFO),
            Triple(R.drawable.ic_qr, R.string.imp_qr, Tone.PRIMARY),
            Triple(R.drawable.ic_image, R.string.imp_gallery, Tone.OK),
            Triple(R.drawable.ic_paste, R.string.imp_paste, Tone.NEUTRAL),
            Triple(R.drawable.ic_link, R.string.imp_link, Tone.PRIMARY),
            Triple(R.drawable.ic_edit, R.string.imp_create, Tone.NEUTRAL)
        )
        val keys = listOf("file", "qr", "gallery", "paste", "link", "create")
        items.forEachIndexed { index, item ->
            val action = SheetAction(item.first, item.second, item.third) { onPick(keys[index]) }
            box.addView(actionRow(context, box, action) { dialog.dismiss() })
        }
        dialog.show()
        return dialog
    }

    private fun actionRow(
        context: Context,
        parent: LinearLayout,
        action: SheetAction,
        dismiss: () -> Unit
    ): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_action, parent, false)
        val icon = row.findViewById<ImageView>(R.id.actionIcon)
        val text = row.findViewById<TextView>(R.id.actionLabel)
        icon.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, action.tone.container))
        icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, action.tone.content))
        icon.setImageResource(action.icon)
        text.setText(action.label)
        row.setOnClickListener {
            dismiss()
            action.run()
        }
        return row
    }
}
