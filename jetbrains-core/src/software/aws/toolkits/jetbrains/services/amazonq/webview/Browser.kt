// Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.amazonq.webview

import com.intellij.openapi.Disposable
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.CefApp
import software.aws.toolkits.jetbrains.services.amazonq.util.createBrowser
import java.util.function.Function

typealias MessageReceiver = Function<String, JBCefJSQuery.Response>

/*
Displays the web view for the Amazon Q tool window
 */
class Browser(parent: Disposable) {

    val jcefBrowser = createBrowser(parent)

    val receiveMessageQuery = JBCefJSQuery.create(jcefBrowser)

    fun init(isGumbyAvailable: Boolean) {
        // register the scheme handler to route http://mynah/ URIs to the resources/assets directory on classpath
        CefApp.getInstance()
            .registerSchemeHandlerFactory(
                "http",
                "mynah",
                AssetResourceHandler.AssetResourceHandlerFactory(),
            )

        loadWebView(isGumbyAvailable)
    }

    fun component() = jcefBrowser.component

    fun post(message: String) =
        jcefBrowser
            .cefBrowser
            .executeJavaScript("window.postMessage(JSON.stringify($message))", jcefBrowser.cefBrowser.url, 0)

    // Load the chat web app into the jcefBrowser
    private fun loadWebView(isGumbyAvailable: Boolean) {
        // setup empty state. The message request handlers use this for storing state
        // that's persistent between page loads.
        jcefBrowser.setProperty("state", "")
        // load the web app
        jcefBrowser.loadHTML(getWebviewHTML(isGumbyAvailable))
    }

    /**
     * Generate index.html for the web view
     * @return HTML source
     */
    private fun getWebviewHTML(isGumbyAvailable: Boolean): String {
        val postMessageToJavaJsCode = receiveMessageQuery.inject("JSON.stringify(message)")

        val jsScripts = """
            <script type="text/javascript" src="$WEB_SCRIPT_URI" defer onload="init()"></script>
            <script type="text/javascript">
                const init = () => {
                    mynahUI.createMynahUI(
                        {
                            postMessage: message => {
                                $postMessageToJavaJsCode
                            }
                        },
                        false, // change to true to enable weaverbird
                        $isGumbyAvailable, // whether /transform is available
                    ); 
                }
            </script>        
        """.trimIndent()

        return """
            <!DOCTYPE html>
            <html>
                <head>
                    <title>AWS Q</title>
                    $jsScripts
                </head>
                <body>
                </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val WEB_SCRIPT_URI = "http://mynah/js/mynah-ui.js"
    }
}
