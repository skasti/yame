package no.skasti.yame.modem

import no.skasti.yame.config.CompositeSetting
import no.skasti.yame.config.IniReadContext
import no.skasti.yame.config.IniWriteContext

data class LoginCredentials(val username: String?, val password: String?) {
    companion object : CompositeSetting<LoginCredentials> {
        override fun loadFromIni(context: IniReadContext) =
            LoginCredentials(context.read("username"), context.read("password"))

        override fun saveToIni(value: LoginCredentials, context: IniWriteContext) {
            context.write("username", value.username)
            context.write("password", value.password)
        }
    }

    init {
        require((username == null) == (password == null)) {
            "username and password must either both be set or both be omitted"
        }
        require(username == null || username.isNotBlank()) {
            "username must not be blank"
        }
        require(password == null || password.isNotEmpty()) {
            "password must not be empty"
        }
        require(username == null || username.length <= HayesModemConfig.MAX_LOGIN_USERNAME_LENGTH) {
            "username must not exceed ${HayesModemConfig.MAX_LOGIN_USERNAME_LENGTH} characters"
        }
        require(password == null || password.length <= HayesModemConfig.MAX_LOGIN_PASSWORD_LENGTH) {
            "password must not exceed ${HayesModemConfig.MAX_LOGIN_PASSWORD_LENGTH} characters"
        }
        require(username == null || username.all { it.code in 0x20..0x7e }) {
            "username must contain only printable ASCII characters"
        }
        require(password == null || password.all { it.code in 0x20..0x7e }) {
            "password must contain only printable ASCII characters"
        }
    }

    override fun toString(): String = "<credentials>"
}
