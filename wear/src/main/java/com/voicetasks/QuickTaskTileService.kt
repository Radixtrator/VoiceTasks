package com.voicetasks

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/** A tile with one big button that opens voice capture. */
class QuickTaskTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout()))
            .build()
        return CallbackToFutureAdapter.getFuture { it.set(tile) }
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        return CallbackToFutureAdapter.getFuture { it.set(resources) }
    }

    private fun layout(): LayoutElementBuilders.LayoutElement {
        val openCapture = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .build(),
            )
            .build()

        val button = LayoutElementBuilders.Box.Builder()
            .setWidth(dp(BUTTON_SIZE))
            .setHeight(dp(BUTTON_SIZE))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(ModifiersBuilders.Clickable.Builder().setId("capture").setOnClick(openCapture).build())
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(ACCENT))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(BUTTON_SIZE / 2)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("Add task")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .setSize(sp(18f))
                            .build(),
                    )
                    .build(),
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .addContent(button)
            .build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val BUTTON_SIZE = 120f
        const val ACCENT = 0xFF7C4DFF.toInt()
    }
}
