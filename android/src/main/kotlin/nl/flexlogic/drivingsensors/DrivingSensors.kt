package nl.flexlogic.drivingsensors

import com.getcapacitor.Logger

class DrivingSensors {

    fun echo(value: String): String {
        Logger.info("Echo", value)

        return value
    }
}
