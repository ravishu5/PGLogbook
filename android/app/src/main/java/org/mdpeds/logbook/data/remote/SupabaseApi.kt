package org.mdpeds.logbook.data.remote

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

/** Raw Supabase endpoints (GoTrue, PostgREST, Storage, Edge Functions); bodies are parsed by [SupabaseBackend]. */
interface SupabaseApi {
    @POST("auth/v1/token")
    suspend fun token(@Query("grant_type") grantType: String, @Body body: RequestBody): Response<ResponseBody>

    @POST("auth/v1/recover")
    suspend fun recover(@Query("redirect_to") redirectTo: String, @Body body: RequestBody): Response<ResponseBody>

    @PUT("auth/v1/user")
    suspend fun updateUser(@Body body: RequestBody): Response<ResponseBody>

    @GET("auth/v1/user")
    suspend fun user(): Response<ResponseBody>

    @POST("auth/v1/logout")
    suspend fun logout(): Response<ResponseBody>

    @POST("rest/v1/rpc/{fn}")
    suspend fun rpc(@Path("fn") fn: String, @Body body: RequestBody): Response<ResponseBody>

    @GET("rest/v1/{table}")
    suspend fun select(@Path("table") table: String, @QueryMap query: Map<String, String>): Response<ResponseBody>

    @POST("storage/v1/object/{bucket}/{path}")
    suspend fun upload(
        @Path("bucket") bucket: String,
        @Path(value = "path", encoded = true) path: String,
        @Header("x-upsert") upsert: String,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @GET("storage/v1/object/authenticated/{bucket}/{path}")
    suspend fun download(
        @Path("bucket") bucket: String,
        @Path(value = "path", encoded = true) path: String,
    ): Response<ResponseBody>

    @POST("functions/v1/{fn}")
    suspend fun function(@Path("fn") fn: String, @Body body: RequestBody): Response<ResponseBody>
}
