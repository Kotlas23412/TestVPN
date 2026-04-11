package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity

class UrlTest {

    private val link: String
    private val timeout: Int

    constructor() : this(DataStore.connectionTestURL, 5000)
    constructor(link: String, timeout: Int = 5000) {
        this.link = link
        this.timeout = timeout
    }

    suspend fun doTest(profile: ProxyEntity): Int {
        return TestInstance(profile, link, timeout).doTest()
    }

}
