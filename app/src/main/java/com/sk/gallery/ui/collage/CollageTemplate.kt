package com.sk.gallery.ui.collage

import android.graphics.RectF

data class CollageTemplate(
    val id: Int,
    val imageCount: Int,
    val bounds: List<RectF>
) {
    companion object {
        fun getTemplatesForCount(count: Int): List<CollageTemplate> {
            return when (count) {
                2 -> listOf(
                    // Horizontal split
                    CollageTemplate(21, 2, listOf(
                        RectF(0f, 0f, 1f, 0.5f),
                        RectF(0f, 0.5f, 1f, 1f)
                    )),
                    // Vertical split
                    CollageTemplate(22, 2, listOf(
                        RectF(0f, 0f, 0.5f, 1f),
                        RectF(0.5f, 0f, 1f, 1f)
                    )),
                    // Diagonal equivalent (simplifying to vertical)
                    CollageTemplate(23, 2, listOf(
                        RectF(0f, 0f, 0.4f, 1f),
                        RectF(0.4f, 0f, 1f, 1f)
                    ))
                )
                3 -> listOf(
                    // 1 top, 2 bottom
                    CollageTemplate(31, 3, listOf(
                        RectF(0f, 0f, 1f, 0.5f),
                        RectF(0f, 0.5f, 0.5f, 1f),
                        RectF(0.5f, 0.5f, 1f, 1f)
                    )),
                    // 2 top, 1 bottom
                    CollageTemplate(32, 3, listOf(
                        RectF(0f, 0f, 0.5f, 0.5f),
                        RectF(0.5f, 0f, 1f, 0.5f),
                        RectF(0f, 0.5f, 1f, 1f)
                    )),
                    // 3 vertical strips
                    CollageTemplate(33, 3, listOf(
                        RectF(0f, 0f, 0.333f, 1f),
                        RectF(0.333f, 0f, 0.666f, 1f),
                        RectF(0.666f, 0f, 1f, 1f)
                    ))
                )
                4 -> listOf(
                    // 2x2 Grid
                    CollageTemplate(41, 4, listOf(
                        RectF(0f, 0f, 0.5f, 0.5f),
                        RectF(0.5f, 0f, 1f, 0.5f),
                        RectF(0f, 0.5f, 0.5f, 1f),
                        RectF(0.5f, 0.5f, 1f, 1f)
                    )),
                    // 1 big top, 3 small bottom
                    CollageTemplate(42, 4, listOf(
                        RectF(0f, 0f, 1f, 0.6f),
                        RectF(0f, 0.6f, 0.333f, 1f),
                        RectF(0.333f, 0.6f, 0.666f, 1f),
                        RectF(0.666f, 0.6f, 1f, 1f)
                    )),
                    // 1 big left, 3 small right
                    CollageTemplate(43, 4, listOf(
                        RectF(0f, 0f, 0.6f, 1f),
                        RectF(0.6f, 0f, 1f, 0.333f),
                        RectF(0.6f, 0.333f, 1f, 0.666f),
                        RectF(0.6f, 0.666f, 1f, 1f)
                    ))
                )
                5 -> listOf(
                    // 2 top, 3 bottom
                    CollageTemplate(51, 5, listOf(
                        RectF(0f, 0f, 0.5f, 0.5f),
                        RectF(0.5f, 0f, 1f, 0.5f),
                        RectF(0f, 0.5f, 0.333f, 1f),
                        RectF(0.333f, 0.5f, 0.666f, 1f),
                        RectF(0.666f, 0.5f, 1f, 1f)
                    )),
                    // 3 top, 2 bottom
                    CollageTemplate(52, 5, listOf(
                        RectF(0f, 0f, 0.333f, 0.5f),
                        RectF(0.333f, 0f, 0.666f, 0.5f),
                        RectF(0.666f, 0f, 1f, 0.5f),
                        RectF(0f, 0.5f, 0.5f, 1f),
                        RectF(0.5f, 0.5f, 1f, 1f)
                    )),
                    // 1 big center, 4 corners (represented roughly)
                    CollageTemplate(53, 5, listOf(
                        RectF(0f, 0f, 0.5f, 0.5f),
                        RectF(0.5f, 0f, 1f, 0.5f),
                        RectF(0f, 0.5f, 0.5f, 1f),
                        RectF(0.5f, 0.5f, 1f, 1f),
                        RectF(0.25f, 0.25f, 0.75f, 0.75f)
                    )) // Note: this overlap template is a bit special, last drawn is on top
                )
                else -> emptyList()
            }
        }
    }
}
