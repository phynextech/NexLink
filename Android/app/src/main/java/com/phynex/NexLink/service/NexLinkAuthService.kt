package com.phynex.NexLink.service

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Handles Google Sign-In via Firebase Auth.
 * After sign-in, the user's UID is used to look up their saved pairId in Firestore
 * so they auto-connect to their PC without scanning QR again.
 */
object NexLinkAuthService {

    private const val TAG = "NexLinkAuth"

    // Web client ID from Google Cloud Console (same project as Firebase)
    // Get this from: Firebase Console → Project Settings → Web API Key section
    // OR: Google Cloud Console → APIs & Services → Credentials → Web client
    const val WEB_CLIENT_ID = "496826457204-REPLACE_WITH_YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser

    private val _isSignedIn = MutableStateFlow(auth.currentUser != null)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
            _isSignedIn.value = firebaseAuth.currentUser != null
        }
    }

    // ─── Google Sign-In ───────────────────────────────────────────────────
    suspend fun signInWithGoogle(context: Context): Result<FirebaseUser> {
        return try {
            val credentialManager = CredentialManager.create(context)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(true)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context, request)
            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdToken.idToken, null)
                val authResult = auth.signInWithCredential(firebaseCredential).await()
                val user = authResult.user!!

                // Save user profile to Firestore
                saveUserToFirestore(user)

                Log.d(TAG, "Signed in: ${user.displayName}")
                Result.success(user)
            } else {
                Result.failure(Exception("Unexpected credential type"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ─── Save user profile ────────────────────────────────────────────────
    private suspend fun saveUserToFirestore(user: FirebaseUser) {
        try {
            val userData = mapOf(
                "uid" to user.uid,
                "email" to (user.email ?: ""),
                "displayName" to (user.displayName ?: ""),
                "photoUrl" to (user.photoUrl?.toString() ?: ""),
                "lastSeen" to System.currentTimeMillis()
            )
            db.collection("users").document(user.uid).set(userData).await()
            Log.d(TAG, "User saved to Firestore")
        } catch (e: Exception) {
            Log.e(TAG, "Firestore save failed: ${e.message}")
        }
    }

    // ─── Load saved pairId for this user ──────────────────────────────────
    /**
     * After sign-in, check Firestore for a saved pairId linked to this user.
     * This enables "permanent connection" — scan QR once, then auto-connect forever.
     */
    suspend fun getSavedPairId(): String? {
        val uid = auth.currentUser?.uid ?: return null
        return try {
            val snap = db.collection("users").document(uid).get().await()
            snap.getString("pairId")
        } catch (e: Exception) {
            Log.e(TAG, "getSavedPairId failed: ${e.message}")
            null
        }
    }

    // ─── Save pairId to user profile ─────────────────────────────────────
    suspend fun savePairId(pairId: String) {
        val uid = auth.currentUser?.uid ?: return
        try {
            db.collection("users").document(uid)
                .update("pairId", pairId, "pairSavedAt", System.currentTimeMillis())
                .await()
            Log.d(TAG, "PairId saved: $pairId")
        } catch (e: Exception) {
            // Document might not exist yet
            db.collection("users").document(uid)
                .set(mapOf("pairId" to pairId, "uid" to uid))
                .await()
        }
    }

    // ─── Sign out ─────────────────────────────────────────────────────────
    fun signOut() {
        auth.signOut()
        Log.d(TAG, "Signed out")
    }

    fun getCurrentUserId(): String? = auth.currentUser?.uid
    fun getCurrentUserName(): String? = auth.currentUser?.displayName
    fun getCurrentUserEmail(): String? = auth.currentUser?.email
    fun getCurrentUserPhoto(): String? = auth.currentUser?.photoUrl?.toString()
}
