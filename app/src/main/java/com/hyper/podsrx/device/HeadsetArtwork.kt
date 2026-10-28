package com.hyper.podsrx.device

import com.hyper.podsrx.R
import com.hyper.podsrx.hook.HeadsetModels

val HeadsetModels.Model.pairArtwork: Int
    get() = if (this === HeadsetModels.AIRPODS_PRO_3) R.drawable.airpodspro3_pair else R.drawable.airpods5_pair

val HeadsetModels.Model.caseArtwork: Int
    get() = if (this === HeadsetModels.AIRPODS_PRO_3) R.drawable.airpodspro3_case else R.drawable.airpods5_case
