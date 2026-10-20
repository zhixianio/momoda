package app.momoda

import app.momoda.DocStore.AccessRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessRequestTest {

    @Test fun android11AndLaterUseAllFilesAccessPage() {
        assertEquals(AccessRequest.ALL_FILES_SETTINGS, DocStore.accessRequest(30, deniedForever = false, rationale = false))
        assertEquals(AccessRequest.ALL_FILES_SETTINGS, DocStore.accessRequest(34, deniedForever = true, rationale = false))
    }

    @Test fun olderSystemsAskWithDialog() {
        assertEquals(AccessRequest.PERMISSION_DIALOG, DocStore.accessRequest(26, deniedForever = false, rationale = false))
        // 拒绝过一次（没勾“不再询问”）：还能再弹
        assertEquals(AccessRequest.PERMISSION_DIALOG, DocStore.accessRequest(29, deniedForever = false, rationale = true))
    }

    @Test fun deniedForeverGoesToAppSettings() {
        // 勾了“不再询问”：系统不再弹窗，点“授权”之前毫无反应
        assertEquals(AccessRequest.APP_SETTINGS, DocStore.accessRequest(26, deniedForever = true, rationale = false))
        // 用户在设置里撤销后系统又允许解释：重新弹窗
        assertEquals(AccessRequest.PERMISSION_DIALOG, DocStore.accessRequest(29, deniedForever = true, rationale = true))
    }
}
