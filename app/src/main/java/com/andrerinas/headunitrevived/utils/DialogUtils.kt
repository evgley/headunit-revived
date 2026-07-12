package com.andrerinas.headunitrevived.utils

import android.R
import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DialogUtils {
    @RequiresApi(Build.VERSION_CODES.M)
    fun showTextInputDialog(
        context: Context,
        titleResId: Int,
        currentValue: String?,
        onResult: (String) -> Unit,
    ) {
        showTextInputDialogWithMessage(context, titleResId, null, currentValue, onResult)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun showTextInputDialogWithMessage(
        context: Context,
        titleResId: Int,
        messageResId: Int?,
        currentValue: String?,
        onResult: (String) -> Unit,
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        if (messageResId != null) {
            val messageText = TextView(context).apply {
                setText(messageResId)
                val textColorAttr = TypedValue()
                context.theme.resolveAttribute(
                    R.attr.textColorSecondary,
                    textColorAttr,
                    true,
                )
                setTextColor(context.resources.getColor(textColorAttr.resourceId, context.theme))
                textSize = 13f
                setPadding(0, 0, 0, 24)
            }
            container.addView(messageText)
        }

        val editText = EditText(context).apply {
            setText(currentValue)
            setSelection(text.length)
        }
        container.addView(editText)

        val dialog = MaterialAlertDialogBuilder(
            context
        )
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val value = editText.text.toString().trim()
                if (value.isNotEmpty()) {
                    onResult(value)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.window?.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        )
        dialog.show()
        editText.requestFocus()
    }
}
