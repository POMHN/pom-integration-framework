import com.sap.gateway.ip.core.customdev.util.Message

def Message processData(Message message) {

    throw new RuntimeException(
            message.getProperty("POM_BusinessError"))
}