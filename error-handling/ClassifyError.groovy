import com.sap.gateway.ip.core.customdev.util.Message
import java.text.SimpleDateFormat
import java.util.HashSet
import java.util.Set

// ==========================================================================
// ErrorClassificationResult
//
// Purpose:
// Standard data structure used to return classification results.
//
// Contains:
// - Error category (TECHNICAL, BUSINESS, UNKNOWN)
// - Severity
// - Human-readable reason
// - Root cause exception
//
// This object is created by the classification engine and consumed
// by the MPL enrichment and monitoring logic.
// ==========================================================================

class ErrorClassificationResult {
    String category
    String severity
    String reason
    Throwable rootCause
}

// ==========================================================================
// ErrorUtils
//
// Purpose:
// Provides reusable helper methods used throughout the framework.
//
// Responsibilities:
// - Extract HTTP status codes
// - Resolve the deepest exception in a cause chain
// - Retrieve root cause class names
// - Retrieve root cause messages
//
// This class contains utility functions only and does not perform
// classification.
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
//
// Purpose:
// Maintains the catalog of known exception types and their
// corresponding classifications.
//
// Responsibilities:
// - Classify known exception types
// - Determine severity
//
// Classification is performed based on exception type inheritance.
//
// Examples:
// - UnknownHostException      -> TECHNICAL
// - SocketTimeoutException    -> TECHNICAL
// - UriNotMatchingException   -> TECHNICAL
//
// Returns UNKNOWN when no matching exception is found.
// ==========================================================================

class ExceptionRegistry {

    static final Map EXCEPTIONS = [

        // ==============================================================
        // Technical Errors
        // ==============================================================
        
        (java.net.SocketTimeoutException): [
            category : "TECHNICAL",
            severity : "HIGH"
        ],

        (java.net.ConnectException): [
            category : "TECHNICAL",
            severity : "HIGH"
        ],

        (java.net.UnknownHostException): [
            category : "TECHNICAL",
            severity : "HIGH"
        ],

        (java.net.NoRouteToHostException): [
            category : "TECHNICAL",
            severity : "HIGH"
        ],

        (javax.net.ssl.SSLHandshakeException): [
            category : "TECHNICAL",
            severity : "HIGH"
        ],

        (javax.net.ssl.SSLException): [
            category : "TECHNICAL",
            severity : "HIGH"
        ],

        // ==============================================================
        // Configuration / Implementation Errors
        // ==============================================================

        (org.apache.olingo.odata2.api.uri.UriNotMatchingException): [
            category : "TECHNICAL",
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
            severity : config.severity,
            reason   : rootCause?.getMessage() ?: "No error message available",
            rootCause: rootCause
        )
    }

    private static ErrorClassificationResult buildUnknownResult(
            Throwable rootCause) {

        return new ErrorClassificationResult(
            category : "UNKNOWN",
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
//
// Purpose:
// Handles business-specific error classifications.
//
// Responsibilities:
// - Evaluate business classification rules
// - Classify BUSINESS errors
// - Return null when no business rule matches
//
// Current Implementation:
// - Uses POM_BusinessError as the business classification trigger
//
// This registry is only evaluated when ExceptionRegistry returns
// UNKNOWN.
// ==========================================================================

class BusinessRuleRegistry {

    static ErrorClassificationResult classify(
        Message message) {

            String businessError = message.getProperty("POM_BusinessError")?.trim()
            
            if (businessError) {
                return new ErrorClassificationResult(
                        category : "BUSINESS",
                        severity : "LOW",
                        reason : businessError
                )
            }
            
            return null
        }
}

// ==========================================================================
// GlobalErrorClassifier
//
// Purpose:
// Central orchestration engine responsible for determining the final
// error classification.
//
// Classification Order:
// 1. ExceptionRegistry
// 2. BusinessRuleRegistry
// 3. HTTP Fallback Rules
// 4. UNKNOWN
//
// Architecture:
//
// processData()
// │
// ▼
// GlobalErrorClassifier
// │
// ├── ExceptionRegistry
// ├── BusinessRuleRegistry
// └── ErrorUtils
//
// The first successful classification wins.
//
// This class acts as the single entry point for all framework
// classification logic.
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
            BusinessRuleRegistry.classify(message)

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
                "POM_Severity",
                result.severity)

        messageLog.addCustomHeaderProperty(
                "POM_HttpStatus",
                httpStatus?.toString() ?: "")

        messageLog.addCustomHeaderProperty(
                "POM_RootCauseClass",
                rootCauseClass)

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