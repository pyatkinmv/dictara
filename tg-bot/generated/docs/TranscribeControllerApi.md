# TranscribeControllerApi

All URIs are relative to *http://localhost:8080*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getJob**](TranscribeControllerApi.md#getJob) | **GET** /jobs/{jobId} |  |
| [**health**](TranscribeControllerApi.md#health) | **GET** /health |  |
| [**transcribe**](TranscribeControllerApi.md#transcribe) | **POST** /transcribe |  |


<a id="getJob"></a>
# **getJob**
> JobResponse getJob(jobId)



### Example
```kotlin
// Import classes:
//import com.dictara.generated.infrastructure.*
//import com.dictara.generated.models.*

val apiInstance = TranscribeControllerApi()
val jobId : kotlin.String = jobId_example // kotlin.String | 
try {
    val result : JobResponse = apiInstance.getJob(jobId)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling TranscribeControllerApi#getJob")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling TranscribeControllerApi#getJob")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **jobId** | **kotlin.String**|  | |

### Return type

[**JobResponse**](JobResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="health"></a>
# **health**
> kotlin.collections.Map&lt;kotlin.String, kotlin.String&gt; health()



### Example
```kotlin
// Import classes:
//import com.dictara.generated.infrastructure.*
//import com.dictara.generated.models.*

val apiInstance = TranscribeControllerApi()
try {
    val result : kotlin.collections.Map<kotlin.String, kotlin.String> = apiInstance.health()
    println(result)
} catch (e: ClientException) {
    println("4xx response calling TranscribeControllerApi#health")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling TranscribeControllerApi#health")
    e.printStackTrace()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

**kotlin.collections.Map&lt;kotlin.String, kotlin.String&gt;**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="transcribe"></a>
# **transcribe**
> SubmitResponse transcribe(model, language, diarize, numSpeakers, summaryMode, transcribeRequest)



### Example
```kotlin
// Import classes:
//import com.dictara.generated.infrastructure.*
//import com.dictara.generated.models.*

val apiInstance = TranscribeControllerApi()
val model : kotlin.String = model_example // kotlin.String | 
val language : kotlin.String = language_example // kotlin.String | 
val diarize : kotlin.Boolean = true // kotlin.Boolean | 
val numSpeakers : kotlin.Int = 56 // kotlin.Int | 
val summaryMode : kotlin.String = summaryMode_example // kotlin.String | 
val transcribeRequest : TranscribeRequest =  // TranscribeRequest | 
try {
    val result : SubmitResponse = apiInstance.transcribe(model, language, diarize, numSpeakers, summaryMode, transcribeRequest)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling TranscribeControllerApi#transcribe")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling TranscribeControllerApi#transcribe")
    e.printStackTrace()
}
```

### Parameters
| **model** | **kotlin.String**|  | [optional] [default to &quot;fast&quot;] |
| **language** | **kotlin.String**|  | [optional] [default to &quot;auto&quot;] |
| **diarize** | **kotlin.Boolean**|  | [optional] [default to false] |
| **numSpeakers** | **kotlin.Int**|  | [optional] |
| **summaryMode** | **kotlin.String**|  | [optional] [default to &quot;auto&quot;] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **transcribeRequest** | [**TranscribeRequest**](TranscribeRequest.md)|  | [optional] |

### Return type

[**SubmitResponse**](SubmitResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

