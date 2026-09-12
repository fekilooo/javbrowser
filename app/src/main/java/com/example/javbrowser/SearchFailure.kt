package com.example.javbrowser

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class SearchTransportException(val errorCode: String) : IOException(errorCode)

/** Exception messages can contain queries/URLs. Retain only bounded, known diagnostic codes. */
object SearchFailure {
    fun code(error: Throwable): String = when (error) {
        is SearchTransportException -> error.errorCode
        is UnknownHostException -> "DNS_FAILURE"
        is SSLException -> "TLS_FAILURE"
        is ConnectException -> "CONNECT_FAILURE"
        is InterruptedIOException -> "HTTP_TIMEOUT"
        is IOException -> "IO_ERROR"
        else -> "PARSER_EXCEPTION"
    }
    fun zh(code: String?): String = when (code) {
        "CF_CHALLENGE" -> "遇到 Cloudflare（CF）人機驗證；尚未取得搜尋結果，請開啟原始搜尋頁完成驗證後重試"
        "CF_BLOCKED" -> "遭 Cloudflare（CF）防火牆封鎖；尚未取得搜尋結果，可能需要網站管理員解除限制"
        "HUMAN_VERIFICATION" -> "網站要求人機驗證；尚未取得搜尋結果"
        "LOGIN_REQUIRED" -> "網站要求登入；尚未取得搜尋結果"
        "HTTP_FORBIDDEN" -> "網站拒絕存取（HTTP 403）；沒有足夠證據確認是 CF，亦不能判定沒有結果"
        "NO_RESULTS" -> "原始搜尋頁明確表示沒有搜尋結果"
        "NO_EXACT_MATCH" -> "已載入的原站結果中沒有完全相同的番號；可檢視原始頁面或載入更多"
        "CODE_NOT_IDENTIFIED" -> "原站有卡片，但未能辨識番號；不能判定沒有結果"
        "DNS_FAILURE" -> "無法解析網站網域，請檢查網域或網路"
        "TLS_FAILURE" -> "安全連線驗證失敗，未略過憑證檢查"
        "CONNECT_FAILURE" -> "無法連上網站伺服器"
        "UNEXPECTED_REDIRECT_HOST" -> "網站轉向未核准網域，請確認網域設定"
        "RESPONSE_TOO_LARGE" -> "搜尋回應超出安全大小限制"
        "HTTP_TIMEOUT", "HTTP_DEADLINE" -> "網站回應逾時"
        "DOM_TIMEOUT" -> "網頁已等待，但搜尋資料尚未就緒"
        "DYNAMIC_RESULTS_NOT_READY" -> "已取得動態網頁外殼，但搜尋內容尚未載入；請開啟原始搜尋頁確認後重試，不代表沒有結果"
        "DOM_STRUCTURE_UNRECOGNIZED" -> "網頁已載入，但找不到可辨識的搜尋卡片"
        "RESULT_CONTAINER_NOT_FOUND" -> "未能辨識原站搜尋列表；尚無法判定有無結果，請檢視原始搜尋頁"
        "DETAIL_LINKS_REJECTED" -> "已找到連結，但不符合本站詳情網址規則"
        "CARD_DATA_MISSING" -> "已找到詳情連結，但卡片容器或標題缺失"
        "UNSUPPORTED_CONTENT_TYPE" -> "網站回傳的不是 HTML 搜尋頁"
        "HTTP_404" -> "搜尋頁不存在，需確認搜尋路徑或網域"
        "HTTP_429" -> "網站暫時限制查詢，請稍後重試"
        "HTTP_502", "HTTP_503", "HTTP_504" -> "網站或上游服務暫時無法回應"
        "AUTH_OR_CHALLENGE" -> "需要在原網站完成驗證後再重試"
        "IO_ERROR" -> "網路傳輸中斷"
        "PARSER_EXCEPTION" -> "搜尋頁解析發生例外"
        else -> "搜尋工作未完成"
    }
    fun en(code: String?): String = when (code) {
        "CF_CHALLENGE" -> "Cloudflare (CF) verification; results have not been retrieved. Open the original page, verify, then retry."
        "CF_BLOCKED" -> "Blocked by the Cloudflare (CF) firewall; results unavailable. The site owner may need to remove the restriction."
        "HUMAN_VERIFICATION" -> "Human verification required; results not retrieved"
        "LOGIN_REQUIRED" -> "Sign-in required; results not retrieved"
        "HTTP_FORBIDDEN" -> "Access denied (HTTP 403); CF is unconfirmed and results remain unknown"
        "NO_RESULTS" -> "The original search page explicitly reports no results"
        "NO_EXACT_MATCH" -> "No exact code match on the loaded pages. View the original page or load more."
        "CODE_NOT_IDENTIFIED" -> "Cards were found but their codes could not be identified; results remain unknown"
        "DNS_FAILURE" -> "Unable to resolve the site hostname"
        "TLS_FAILURE" -> "TLS validation failed; certificate checks remain enabled"
        "CONNECT_FAILURE" -> "Unable to connect to the site"
        "UNEXPECTED_REDIRECT_HOST" -> "Redirected to an unapproved hostname"
        "RESPONSE_TOO_LARGE" -> "Response exceeded the safety size limit"
        "HTTP_TIMEOUT", "HTTP_DEADLINE" -> "Site response timed out"
        "DOM_TIMEOUT" -> "Search data did not become ready in time"
        "DYNAMIC_RESULTS_NOT_READY" -> "The dynamic page shell loaded but search content is not ready. Check the original page and retry; this does not mean no results."
        "DOM_STRUCTURE_UNRECOGNIZED" -> "Loaded page has no recognised search cards"
        "RESULT_CONTAINER_NOT_FOUND" -> "Search list could not be read; results remain unknown. View the original page."
        "DETAIL_LINKS_REJECTED" -> "Links did not match the site's detail URL rules"
        "CARD_DATA_MISSING" -> "Detail links have no recognised card or title"
        "UNSUPPORTED_CONTENT_TYPE" -> "The site returned a non-HTML response"
        "HTTP_404" -> "Search page not found; check route or domain"
        "HTTP_429" -> "Site rate limit; wait before retrying"
        "HTTP_502", "HTTP_503", "HTTP_504" -> "Site or upstream service unavailable"
        "AUTH_OR_CHALLENGE" -> "Complete verification on the original site, then retry"
        "IO_ERROR" -> "Network transfer interrupted"
        "PARSER_EXCEPTION" -> "Search page parsing exception"
        else -> "Search did not finish"
    }
}
