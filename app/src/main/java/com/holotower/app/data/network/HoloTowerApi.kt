package com.holotower.app.data.network

import com.holotower.app.data.model.CatalogPage
import com.holotower.app.data.model.ThreadResponse
import retrofit2.http.GET
import retrofit2.http.Path

interface HoloTowerApi {
    @GET("{board}/catalog.json")
    suspend fun getCatalog(@Path("board") board: String): List<CatalogPage>

    @GET("{board}/res/{threadNo}.json")
    suspend fun getThread(
        @Path("board") board: String,
        @Path("threadNo") threadNo: Long
    ): ThreadResponse
}
