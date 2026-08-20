package it.paolo.passwordsafe

import android.app.backup.BackupManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VaultItem(val id:String=UUID.randomUUID().toString(),var title:String,var username:String,var password:String,var url:String="",var notes:String="",var category:String="Altro",var type:String="LOGIN")

class VaultStore(private val context:Context){
    private companion object {
        const val MASTER_PBKDF2_ITERATIONS = 600_000
        const val LEGACY_BACKUP_PBKDF2_ITERATIONS = 250_000
        @Volatile private var sharedSessionKey: ByteArray? = null
    }
    private val file=context.filesDir.resolve("vault.bin")
    private val prefs=context.getSharedPreferences("vault_security_v2",Context.MODE_PRIVATE)
    private val legacyAlias="passwordsafe_vault_key"
    private val biometricAlias="passwordsafe_biometric_rsa_v2"
    private var sessionKey: ByteArray?
        get() = sharedSessionKey
        set(value) { sharedSessionKey = value }
    val modern:Boolean get()=prefs.getBoolean("modern",false)

    fun loadLegacy():MutableList<VaultItem>{if(!file.exists())return mutableListOf();return fromJson(String(decryptLegacy(file.readBytes())))}
    fun migrateLegacy(items:List<VaultItem>,masterPassword:String,recoveryCode:String){val dek=random(32);storeWrapped("password",dek,masterPassword);storeWrapped("recovery",dek,recoveryCode);runCatching{storeBiometricWrap(dek)};sessionKey=dek;prefs.edit().putBoolean("modern",true).apply();save(items)}
    fun initialize(items:List<VaultItem>,masterPassword:String,recoveryCode:String)=migrateLegacy(items,masterPassword,recoveryCode)
    fun unlockWithPassword(password:String):Boolean=runCatching{sessionKey=unwrap("password",password);true}.getOrDefault(false)
    fun unlockWithRecovery(code:String):Boolean=runCatching{sessionKey=unwrap("recovery",code);true}.getOrDefault(false)
    fun changePassword(newPassword:String){storeWrapped("password",requireNotNull(sessionKey),newPassword)}
    fun load():MutableList<VaultItem>{val key=requireNotNull(sessionKey);if(!file.exists())return mutableListOf();return fromJson(String(decrypt(file.readBytes(),key)))}
    fun save(items:List<VaultItem>){file.writeBytes(encrypt(toJson(items).toByteArray(),requireNotNull(sessionKey)));runCatching{BackupManager(context).dataChanged()}}
    fun lock(){sessionKey?.fill(0);sessionKey=null}
    fun isUnlocked():Boolean=sessionKey!=null

    fun biometricCipher():Cipher?=runCatching{val wrapped=unb64(prefs.getString("biometric_wrap","")!!);if(wrapped.isEmpty())return null;val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};Cipher.getInstance("RSA/ECB/PKCS1Padding").apply{init(Cipher.DECRYPT_MODE,ks.getKey(biometricAlias,null))}}.getOrNull()
    fun unlockWithBiometric(cipher:Cipher):Boolean=runCatching{sessionKey=cipher.doFinal(unb64(prefs.getString("biometric_wrap","")!!));true}.getOrDefault(false)

    fun createBackup(items:List<VaultItem>,password:String):ByteArray{val salt=random(16);val key=derive(password,salt,MASTER_PBKDF2_ITERATIONS);val payload=encrypt(toJson(items).toByteArray(),key);key.fill(0);return JSONObject().put("format",2).put("iterations",MASTER_PBKDF2_ITERATIONS).put("salt",b64(salt)).put("data",b64(payload)).toString().toByteArray()}
    fun restoreBackup(bytes:ByteArray,password:String):MutableList<VaultItem>{val o=JSONObject(String(bytes));val salt=unb64(o.getString("salt"));val iterations=if(o.getInt("format")==1) LEGACY_BACKUP_PBKDF2_ITERATIONS else MASTER_PBKDF2_ITERATIONS;val key=derive(password,salt,o.optInt("iterations",iterations));val clear=if(o.getInt("format")==1){val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,unb64(o.getString("iv"))));c.doFinal(unb64(o.getString("data")))}else decrypt(unb64(o.getString("data")),key);key.fill(0);return fromJson(String(clear))}

    private fun storeWrapped(prefix:String,dek:ByteArray,secret:String){val salt=random(16);val key=derive(secret,salt,MASTER_PBKDF2_ITERATIONS);prefs.edit().putString("${prefix}_salt",b64(salt)).putString("${prefix}_wrap",b64(encrypt(dek,key))).apply();key.fill(0);runCatching{BackupManager(context).dataChanged()}}
    private fun unwrap(prefix:String,secret:String):ByteArray{val salt=unb64(prefs.getString("${prefix}_salt",null)?:error("Dati mancanti"));val key=derive(secret,salt,MASTER_PBKDF2_ITERATIONS);val out=decrypt(unb64(prefs.getString("${prefix}_wrap",null)?:error("Dati mancanti")),key);key.fill(0);return out}
    private fun storeBiometricWrap(dek:ByteArray){val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};if(!ks.containsAlias(biometricAlias)){val builder=KeyGenParameterSpec.Builder(biometricAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true);if(android.os.Build.VERSION.SDK_INT>=30)builder.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)else builder.setUserAuthenticationValidityDurationSeconds(-1);val g=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");g.initialize(builder.build());g.generateKeyPair()};val c=Cipher.getInstance("RSA/ECB/PKCS1Padding");c.init(Cipher.ENCRYPT_MODE,ks.getCertificate(biometricAlias).publicKey);prefs.edit().putString("biometric_wrap",b64(c.doFinal(dek))).apply()}
    private fun encrypt(clear:ByteArray,key:ByteArray):ByteArray{val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"));return c.iv+c.doFinal(clear)}
    private fun decrypt(data:ByteArray,key:ByteArray):ByteArray{require(data.size>28);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,data.copyOfRange(0,12)));return c.doFinal(data.copyOfRange(12,data.size))}
    private fun decryptLegacy(data:ByteArray):ByteArray{val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,ks.getKey(legacyAlias,null),GCMParameterSpec(128,data.copyOfRange(0,12)));return c.doFinal(data.copyOfRange(12,data.size))}
    private fun derive(secret:String,salt:ByteArray,iterations:Int):ByteArray{val spec=PBEKeySpec(secret.toCharArray(),salt,iterations,256);val out=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded;spec.clearPassword();return out}
    private fun random(n:Int)=ByteArray(n).also{SecureRandom().nextBytes(it)}
    private fun toJson(items:List<VaultItem>)=JSONArray().apply{items.forEach{i->put(JSONObject().put("id",i.id).put("title",i.title).put("username",i.username).put("password",i.password).put("url",i.url).put("notes",i.notes).put("category",i.category).put("type",i.type))}}.toString()
    private fun fromJson(json:String):MutableList<VaultItem>{val a=JSONArray(json);return MutableList(a.length()){n->a.getJSONObject(n).let{o->VaultItem(o.getString("id"),o.getString("title"),o.optString("username"),o.optString("password"),o.optString("url"),o.optString("notes"),o.optString("category","Altro"),o.optString("type","LOGIN"))}}}
    private fun b64(v:ByteArray)=Base64.encodeToString(v,Base64.NO_WRAP)
    private fun unb64(v:String)=Base64.decode(v,Base64.NO_WRAP)
}
