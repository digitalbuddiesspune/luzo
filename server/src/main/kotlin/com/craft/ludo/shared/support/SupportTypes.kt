package com.craft.ludo.shared.support

import java.util.UUID

fun newId(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(20)}"
