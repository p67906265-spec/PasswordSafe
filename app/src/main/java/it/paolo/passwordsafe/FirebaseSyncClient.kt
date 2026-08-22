package it.paolo.passwordsafe

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.GCMParameterSpec

data class SyncSession(val token:String,val uid:String,val refreshToken:String)
data class SyncRequest(val id:String,val device:String,val publicKey:String,val expiresAt:Long,val direction:String="ANDROID_TO_WINDOWS",val manifest:Map<String,Long> = emptyMap())

class FirebaseSyncClient {
    companion object { private const val API_KEY="AIzaSyBvzFrou5RwJuJJobXFFl0bXZ58zsmw2hM";private const val DB="https://passwordsafe-sync-420eb-default-rtdb.europe-west1.firebasedatabase.app" }
    fun login(email:String,password:String,create:Boolean=false):SyncSession {val method=if(create)"signUp" else "signInWithPassword";val body=JSONObject().put("email",email).put("password",password).put("returnSecureToken",true);val o=JSONObject(request("https://identitytoolkit.googleapis.com/v1/accounts:$method?key=$API_KEY","POST",body.toString()));return SyncSession(o.getString("idToken"),o.getString("localId"),o.getString("refreshToken"))}
    fun refresh(refreshToken:String):SyncSession {val body="grant_type=refresh_token&refresh_token="+java.net.URLEncoder.encode(refreshToken,"UTF-8");val o=JSONObject(request("https://securetoken.googleapis.com/v1/token?key=$API_KEY","POST",body,"application/x-www-form-urlencoded"));return SyncSession(o.getString("id_token"),o.getString("user_id"),o.getString("refresh_token"))}
    fun pending(s:SyncSession,channel:String):SyncRequest? {val raw=request("$DB/users/${s.uid}/channels/$channel/request.json?auth=${s.token}","GET",null);if(raw=="null"||raw.isBlank())return null;val o=JSONObject(raw);if(o.optLong("expiresAt")<System.currentTimeMillis())return null;val m=mutableMapOf<String,Long>();o.optJSONObject("manifest")?.let{x->x.keys().forEach{k->m[k]=x.optLong(k,0)}};return SyncRequest(o.getString("id"),o.optString("device","PC Windows"),o.getString("challenge"),o.getLong("expiresAt"),o.optString("direction","ANDROID_TO_WINDOWS"),m)}
    fun approve(s:SyncSession,channel:String,r:SyncRequest,clear:ByteArray){val pub=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(unb64(r.publicKey)));val aes=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey();val nonce=ByteArray(12).also{SecureRandom().nextBytes(it)};val encrypted=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,aes,GCMParameterSpec(128,nonce))}.doFinal(clear);val oaep=OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT);val wrap=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply{init(Cipher.ENCRYPT_MODE,pub,oaep)}.doFinal(aes.encoded);val data=JSONObject().put("key",b64(wrap)).put("nonce",b64(nonce)).put("cipher",b64(encrypted)).toString();val response=JSONObject().put("requestId",r.id).put("approved",true).put("data",data).put("updatedAt",System.currentTimeMillis());request("$DB/users/${s.uid}/channels/$channel/response.json?auth=${s.token}","PUT",response.toString());request("$DB/users/${s.uid}/channels/$channel/request.json?auth=${s.token}","DELETE",null)}
    fun approveAndReceive(s:SyncSession,channel:String,r:SyncRequest,clear:ByteArray):ByteArray? {
        val incomingKeys=if(r.direction=="BIDIRECTIONAL")java.security.KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair() else null
        val outgoing=filterDelta(clear,r.manifest);val pub=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(unb64(r.publicKey)));val aes=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey();val nonce=ByteArray(12).also{SecureRandom().nextBytes(it)};val encrypted=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,aes,GCMParameterSpec(128,nonce))}.doFinal(outgoing);val oaep=OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT);val wrap=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply{init(Cipher.ENCRYPT_MODE,pub,oaep)}.doFinal(aes.encoded);val data=JSONObject().put("key",b64(wrap)).put("nonce",b64(nonce)).put("cipher",b64(encrypted)).toString();val response=JSONObject().put("requestId",r.id).put("approved",true).put("data",data).put("manifest",makeManifest(clear)).put("sentCount",org.json.JSONArray(String(outgoing)).length()).put("updatedAt",System.currentTimeMillis());if(incomingKeys!=null)response.put("uploadChallenge",b64(incomingKeys.public.encoded));request("$DB/users/${s.uid}/channels/$channel/response.json?auth=${s.token}","PUT",response.toString());request("$DB/users/${s.uid}/channels/$channel/request.json?auth=${s.token}","DELETE",null)
        if(incomingKeys==null)return null
        repeat(100){Thread.sleep(3000);val raw=request("$DB/users/${s.uid}/channels/$channel/upload.json?auth=${s.token}","GET",null);if(raw!="null"&&raw.isNotBlank()){val root=JSONObject(raw);if(root.optString("requestId")==r.id){val packed=JSONObject(root.getString("data"));val unwrap=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply{init(Cipher.DECRYPT_MODE,incomingKeys.private,oaep)}.doFinal(unb64(packed.getString("key")));val result=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,javax.crypto.spec.SecretKeySpec(unwrap,"AES"),GCMParameterSpec(128,unb64(packed.getString("nonce"))))}.doFinal(unb64(packed.getString("cipher")));unwrap.fill(0);request("$DB/users/${s.uid}/channels/$channel/upload.json?auth=${s.token}","DELETE",null);return result}}}
        throw IllegalStateException("Windows non ha inviato i dati entro 5 minuti")
    }
    fun deny(s:SyncSession,channel:String,r:SyncRequest){val o=JSONObject().put("requestId",r.id).put("approved",false).put("updatedAt",System.currentTimeMillis());request("$DB/users/${s.uid}/channels/$channel/response.json?auth=${s.token}","PUT",o.toString());request("$DB/users/${s.uid}/channels/$channel/request.json?auth=${s.token}","DELETE",null)}
    private fun makeManifest(clear:ByteArray):JSONObject{val a=org.json.JSONArray(String(clear));return JSONObject().apply{repeat(a.length()){val o=a.getJSONObject(it);put(o.getString("id"),o.optLong("updatedAt",0))}}}
    private fun filterDelta(clear:ByteArray,other:Map<String,Long>):ByteArray{val a=org.json.JSONArray(String(clear));val out=org.json.JSONArray();repeat(a.length()){val o=a.getJSONObject(it);if(o.optLong("updatedAt",0)>other.getOrDefault(o.getString("id"),-1))out.put(o)};return out.toString().toByteArray()}
    private fun request(url:String,method:String,body:String?,contentType:String="application/json"):String{val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod=method;c.connectTimeout=15000;c.readTimeout=15000;c.setRequestProperty("Content-Type",contentType);if(body!=null){c.doOutput=true;c.outputStream.use{it.write(body.toByteArray())}};val code=c.responseCode;val text=(if(code in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};if(code !in 200..299)throw IllegalStateException(runCatching{JSONObject(text).optJSONObject("error")?.optString("message")}.getOrNull()?.ifBlank{null}?:"Errore Firebase $code");return text}
    private fun b64(v:ByteArray)=Base64.encodeToString(v,Base64.NO_WRAP or Base64.URL_SAFE)
    private fun unb64(v:String)=Base64.decode(v,Base64.NO_WRAP or Base64.URL_SAFE)
}
