package com.gozar

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.util.Locale

class AccountSheet(
    private val context: Context,
    private val select: (SavedFoxAccount, BottomSheetDialog) -> Unit,
    private val add: (BottomSheetDialog) -> Unit
) {
    fun show(accounts: List<SavedFoxAccount>): BottomSheetDialog {
        val sheet = BottomSheetDialog(context, R.style.ThemeOverlay_Gozar_BottomSheet)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_accounts, null)
        val rows = view.findViewById<LinearLayout>(R.id.accountRows)
        for (account in accounts) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_account, rows, false)
            val card = row.findViewById<MaterialCardView>(R.id.accountCard)
            row.findViewById<TextView>(R.id.accountAvatar).text = account.email.trim().firstOrNull()?.toString()?.uppercase(Locale.ROOT) ?: "?"
            row.findViewById<TextView>(R.id.accountEmail).text = account.email
            row.findViewById<TextView>(R.id.accountStatus).text = context.getString(
                if (account.active) R.string.account_active else R.string.account_tap_switch
            )
            row.findViewById<View>(R.id.accountCheck).visibility = if (account.active) View.VISIBLE else View.INVISIBLE
            if (account.active) {
                card.strokeWidth = (context.resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(1)
                card.strokeColor = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
            }
            card.setOnClickListener { if (!account.active) select(account, sheet) }
            rows.addView(row)
        }
        view.findViewById<View>(R.id.accountAdd).setOnClickListener { add(sheet) }
        sheet.setContentView(view)
        sheet.behavior.maxHeight = (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
        sheet.show()
        return sheet
    }
}
