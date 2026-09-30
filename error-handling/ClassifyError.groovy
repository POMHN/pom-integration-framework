import com.sap.gateway.ip.core.customdev.util.Message
import java.text.SimpleDateFormat
import java.util.HashSet
import java.util.Set

// ==========================================================================
// ErrorClassificationResult
// ==========================================================================

class ErrorClassificationResult {
    String category
    boolean retryable
    String severity
    String reason
    Throwable rootCause
}

// ==========================================================================
// ErrorUtils
// ==========================================================================

class ErrorUtils {

    static Integer getHttpStatus(Message message) {

        def headers = message.getHeaders()
        def properties = message.getProperties()

        def httpCode =
                headers.get("CamelHttpResponseCode") ?:
                properties.get("CamelHttpResponseCode")

        if (httpCode == null) {
            return null
        }

        try {
            return httpCode.toString().toInteger()
        }
        catch (Exception ignored) {
            return null
        }
    }

    static Throwable getRootCause(Throwable exception) {

        if (exception == null) {
            return null
        }

        Throwable current = exception

        Set<Throwable> visited = new HashSet<>()

        while (current.cause != null &&
               !visited.contains(current.cause)) {

            visited.add(current)
            current = current.cause
        }

        return current
    }

    static String getRootCauseClassName(Throwable exception) {

        Throwable rootCause = getRootCause(exception)

        return rootCause?.getClass()?.getSimpleName() ?: "Unknown"
    }

    static String getRootCauseMessage(Throwable exception) {

        Throwable rootCause = getRootCause(exception)

        return rootCause?.getMessage() ?: "No error message available"
    }
}

// ==========================================================================
// ExceptionRegistry
// ==========================================================================

class ExceptionRegistry {

    static final Map EXCEPTIONS = [

        // ==============================================================
        // Technical Errors
        // ==============================================================
        
        (java.net.SocketTimeoutException): [
            category : "TECHNICAL",
            retryable: true,
            severity : "HIGH"
        ],

        (java.net.ConnectException): [
            category : "TECHNICAL",
            retryable: true,
            severity : "HIGH"
        ],

        (java.net.UnknownHostException): [
            category : "TECHNICAL",
            retryable: true,
            severity : "HIGH"
        ],

        (java.net.NoRouteToHostException): [
            category : "TECHNICAL",
            retryable: true,
            severity : "HIGH"
        ],

        (javax.net.ssl.SSLHandshakeException): [
            category : "TECHNICAL",
            retryable: false,
            severity : "HIGH"
        ],

        (javax.net.ssl.SSLException): [
            category : "TECHNICAL",
            retryable: false,
            severity : "HIGH"
        ],

        // ==============================================================
        // Functional Errors
        // ==============================================================

        (org.apache.olingo.odata2.api.uri.UriNotMatchingException): [
            category : "FUNCTIONAL",
            retryable: false,
            severity : "MEDIUM"
        ]
    ]

    static ErrorClassificationResult classify(Throwable exception) {

        Throwable rootCause = ErrorUtils.getRootCause(exception)

        if (rootCause == null) {
            return buildUnknownResult(null)
        }

        def exceptionClass = rootCause.getClass()

        def matchingClasses = EXCEPTIONS.keySet().findAll {
            it.isAssignableFrom(exceptionClass)
        }

        if (matchingClasses.isEmpty()) {
            return buildUnknownResult(rootCause)
        }

        def bestMatch = matchingClasses.min {
            inheritanceDistance(exceptionClass, it)
        }

        Map config = EXCEPTIONS[bestMatch]

        return new ErrorClassificationResult(
            category : config.category,
            retryable: config.retryable,
            severity : config.severity,
            reason   : rootCause?.getMessage() ?: "No error message available",
            rootCause: rootCause
        )
    }

    private static ErrorClassificationResult buildUnknownResult(
            Throwable rootCause) {

        return new ErrorClassificationResult(
            category : "UNKNOWN",
            retryable: false,
            severity : "MEDIUM",
            reason   : rootCause?.getMessage()
                       ?: "No error message available",
            rootCause: rootCause
        )
    }

    private static int inheritanceDistance(
            Class actualClass,
            Class registeredClass) {

        int distance = 0

        Class current = actualClass

        while (current != null) {

            if (current == registeredClass) {
                return distance
            }

            current = current.getSuperclass()
            distance++
        }

        return Integer.MAX_VALUE
    }
}

// ==========================================================================
// BusinessRuleRegistry
// ==========================================================================

class BusinessRuleRegistry {

    static ErrorClassificationResult classify(
        Message message,
        String errorMessage) {
            
            errorMessage = errorMessage?.trim()

            // ------------------------------------------------------------------
            // Classification order:
            // 1. ExceptionRegistry
            // 2. BusinessRuleRegistry
            // 3. HTTP fallback rules
            // 4. UNKNOWN
            //
            // Purpose:
            // Handles business-specific validation scenarios that cannot be
            // reliably identified through exception types alone.
            //
            // Future examples:
            // - Employee already exists
            // - Posting period closed
            // - Cost center inactive
            // - Duplicate candidate
            // - Business process not allowed
            //
            // Return null when no business rule matches.
            // ------------------------------------------------------------------
             
            String businessError = message.getProperty("POM_BusinessError")?.trim()
            if (businessError) {
                return new ErrorClassificationResult(
                        category : "BUSINESS",
                        retryable: false,
                        severity : "LOW",
                        reason : businessError
                )
            }
            
            return null
        }
}

// ==========================================================================
// GlobalErrorClassifier
// ==========================================================================

class GlobalErrorClassifier {

    static ErrorClassificationResult classify(
            Message message,
            Throwable exception) {

        ErrorClassificationResult result =
                ExceptionRegistry.classify(exception)

        if (!"UNKNOWN".equals(result.category)) {
            return result
        }

        ErrorClassificationResult businessResult =
            BusinessRuleRegistry.classify(
                message,
                result.reason)

        if (businessResult != null) {
            return businessResult
        }

        Integer httpStatus = ErrorUtils.getHttpStatus(message)

        if (httpStatus != null) {

            switch (httpStatus) {

                case 500:
                case 502:
                case 503:
                case 504:

                    return new ErrorClassificationResult(
                        category : "TECHNICAL",
                        retryable: true,
                        severity : "HIGH",
                        reason   :
                                "Target system returned HTTP ${httpStatus}",
                        rootCause: result.rootCause
                    )
            }
        }

        return result
    }
}

// ==========================================================================
// Main Script
// ==========================================================================

def Message processData(Message message) {

    Throwable exception =
            message.getProperty("CamelExceptionCaught") as Throwable

    ErrorClassificationResult result =
            GlobalErrorClassifier.classify(
                    message,
                    exception)

    boolean debugMode =
        "true".equalsIgnoreCase(
                message.getProperty("POM_DebugMode")?.toString())

    String interfaceName =
            message.getHeader("POM_InterfaceName", String) ?:
            message.getHeader("SAP_MessageType", String) ?:
            "UnknownInterface"

    String businessId =
            message.getHeader("POM_BusinessId", String) ?:
            message.getHeader("SAP_ApplicationID", String) ?:
            "UnknownBusinessId"

    String interfaceId =
            message.getHeader("POM_InterfaceId", String) ?:
            interfaceName

    String rootCauseClass =
            result.rootCause?.getClass()?.getSimpleName() ?: "Unknown"

    String rootCauseMessage =
            result.reason ?: "No error message available"

    Integer httpStatus =
            ErrorUtils.getHttpStatus(message)

    String errorMessage =
            "[${result.category}] " +
            "Interface=${interfaceName} | " +
            "BusinessId=${businessId} | " +
            "HTTP=${httpStatus ?: 'n/a'} | " +
            "Cause=${rootCauseClass} | " +
            "Error=${rootCauseMessage}"

    [
        "POM_ErrorCategory"    : result.category,
        "POM_Retryable"        : result.retryable.toString(),
        "POM_Severity"         : result.severity,
        "POM_HttpStatus"       : httpStatus?.toString() ?: "",
        "POM_RootCauseClass"   : rootCauseClass,
        "POM_RootCauseMessage" : rootCauseMessage,
        "POM_ErrorMessage"     : errorMessage
    ].each { key, value ->

        message.setHeader(key, value)
        message.setProperty(key, value)
    }
    
    String dsName = "DS_MANUAL_RETRY_" + interfaceId

    if (dsName.length() > 40) {
        dsName = dsName.substring(0, 40)
    }
    
    String dateString =
            new SimpleDateFormat("yyyyMMddHHmmss")
                    .format(new Date())

    def messageLog = messageLogFactory.getMessageLog(message)

    if (messageLog != null) {

        messageLog.addCustomHeaderProperty(
                "POM_ErrorCategory",
                result.category)

        messageLog.addCustomHeaderProperty(
                "POM_Interface",
                interfaceName)

        messageLog.addCustomHeaderProperty(
                "POM_BusinessId",
                businessId)
                
        messageLog.addCustomHeaderProperty(
                "POM_DataStoreName",
                dsName)

        messageLog.addCustomHeaderProperty(
                "POM_DataStoreEntryId",
                "${interfaceId}_${businessId}_${dateString}")

        messageLog.addCustomHeaderProperty(
                "POM_Retryable",
                result.retryable.toString())

        messageLog.addCustomHeaderProperty(
                "POM_Severity",
                result.severity)

        messageLog.addCustomHeaderProperty(
                "POM_HttpStatus",
                httpStatus?.toString() ?: "")

        messageLog.addCustomHeaderProperty(
                "POM_RootCauseClass",
                rootCauseClass)

        if (debugMode) {
                messageLog.addCustomHeaderProperty(
                        "POM_RootCauseFullClass",
                        result.rootCause?.getClass()?.getName() ?: "Unknown")
        }

        messageLog.addCustomHeaderProperty(
                "POM_RootCauseMessage",
                rootCauseMessage)

        messageLog.addCustomHeaderProperty(
                "POM_ErrorMessage",
                errorMessage)

        messageLog.setStringProperty(
                "POM_ErrorCategory",
                result.category)

        messageLog.setStringProperty(
                "SAP_MessageProcessingLogCustomStatus",
                "${result.category}_ERROR")
    }
    
    message.setProperty(
            "POM_DataStoreName",
            dsName)
    
    message.setProperty(
            "POM_DataStore_EntryID",
            "${interfaceId}_${businessId}_${dateString}")
            
    message.setHeader(
        "POM_DataStoreName",
        dsName)

    message.setHeader(
            "POM_DataStore_EntryID",
            "${interfaceId}_${businessId}_${dateString}")

    return message
}