package com.webunime.tv.ui.browse

import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ObjectAdapter

/** ListRow khusus hero — tanpa header judul, ikut scroll di VerticalGridView. */
class HeroListRow(
    header: HeaderItem,
    adapter: ObjectAdapter,
) : ListRow(header, adapter)
