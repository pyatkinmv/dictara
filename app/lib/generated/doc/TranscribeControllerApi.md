# dictara_api.api.TranscribeControllerApi

## Load the API package
```dart
import 'package:dictara_api/api.dart';
```

All URIs are relative to *http://localhost:8080*

Method | HTTP request | Description
------------- | ------------- | -------------
[**getJob**](TranscribeControllerApi.md#getjob) | **GET** /jobs/{jobId} | 
[**health**](TranscribeControllerApi.md#health) | **GET** /health | 
[**transcribe**](TranscribeControllerApi.md#transcribe) | **POST** /transcribe | 


# **getJob**
> JobResponse getJob(jobId)



### Example
```dart
import 'package:dictara_api/api.dart';

final api_instance = TranscribeControllerApi();
final jobId = jobId_example; // String | 

try {
    final result = api_instance.getJob(jobId);
    print(result);
} catch (e) {
    print('Exception when calling TranscribeControllerApi->getJob: $e\n');
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **jobId** | **String**|  | 

### Return type

[**JobResponse**](JobResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **health**
> Map<String, String> health()



### Example
```dart
import 'package:dictara_api/api.dart';

final api_instance = TranscribeControllerApi();

try {
    final result = api_instance.health();
    print(result);
} catch (e) {
    print('Exception when calling TranscribeControllerApi->health: $e\n');
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

**Map<String, String>**

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

# **transcribe**
> SubmitResponse transcribe(model, language, diarize, numSpeakers, summaryMode, transcribeRequest)



### Example
```dart
import 'package:dictara_api/api.dart';

final api_instance = TranscribeControllerApi();
final model = model_example; // String | 
final language = language_example; // String | 
final diarize = true; // bool | 
final numSpeakers = 56; // int | 
final summaryMode = summaryMode_example; // String | 
final transcribeRequest = TranscribeRequest(); // TranscribeRequest | 

try {
    final result = api_instance.transcribe(model, language, diarize, numSpeakers, summaryMode, transcribeRequest);
    print(result);
} catch (e) {
    print('Exception when calling TranscribeControllerApi->transcribe: $e\n');
}
```

### Parameters

Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **model** | **String**|  | [optional] [default to 'fast']
 **language** | **String**|  | [optional] [default to 'auto']
 **diarize** | **bool**|  | [optional] [default to false]
 **numSpeakers** | **int**|  | [optional] 
 **summaryMode** | **String**|  | [optional] [default to 'auto']
 **transcribeRequest** | [**TranscribeRequest**](TranscribeRequest.md)|  | [optional] 

### Return type

[**SubmitResponse**](SubmitResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

