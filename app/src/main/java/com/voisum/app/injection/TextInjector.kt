package com.voisum.app.injection

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Handles text injection into focused text fields.
 * Priority 1: Direct injection via ACTION_SET_TEXT
 * Priority 2: Clipboard fallback with banner notification
 */
class TextInjector(private val context: Context) {

    data class InjectionResult(
        val success: Boolean,
        val usedClipboard: Boolean,
        val previousText: String
    )

    /**
     * Inject text into the given accessibility node.
     * Saves the current text content for undo support.
     *
     * CRITICAL BUG FIX #9: Read all needed properties from source before calling recycle().
     */
    fun injectText(
        node: AccessibilityNodeInfo,
        text: String
    ): InjectionResult {
        // Read existing text before any mutation
        val previousText = node.text?.toString() ?: ""

        // Priority 1: Direct injection via ACTION_SET_TEXT
        val bundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val directSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

        if (directSuccess) {
            return InjectionResult(
                success = true,
                usedClipboard = false,
                previousText = previousText
            )
        }

        // Priority 2: Clipboard fallback
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Voisum", text))

        return InjectionResult(
            success = true,
            usedClipboard = true,
            previousText = previousText
        )
    }

    /**
     * Restore previous text (undo).
     */
    fun undoInjection(
        node: AccessibilityNodeInfo,
        previousText: String
    ): Boolean {
        val bundle = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                previousText
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }
}
