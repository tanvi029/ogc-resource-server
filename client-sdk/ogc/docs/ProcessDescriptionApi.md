# openapi_client.ProcessDescriptionApi

All URIs are relative to *https://geoserver.dx.ugix.org.in*

Method | HTTP request | Description
------------- | ------------- | -------------
[**get_process_description**](ProcessDescriptionApi.md#get_process_description) | **GET** /processes/{processId} | retrieve a process description


# **get_process_description**
> Process get_process_description(process_id)

retrieve a process description

The process description contains information about inputs and outputs and a link to the execution-endpoint for the process.

### Example


```python
import openapi_client
from openapi_client.models.process import Process
from openapi_client.rest import ApiException
from pprint import pprint

# Defining the host is optional and defaults to https://geoserver.dx.ugix.org.in
# See configuration.py for a list of all supported configuration parameters.
configuration = openapi_client.Configuration(
    host = "https://geoserver.dx.ugix.org.in"
)


# Enter a context with an instance of the API client
with openapi_client.ApiClient(configuration) as api_client:
    # Create an instance of the API class
    api_instance = openapi_client.ProcessDescriptionApi(api_client)
    process_id = 'process_id_example' # str | local identifier of a process

    try:
        # retrieve a process description
        api_response = api_instance.get_process_description(process_id)
        print("The response of ProcessDescriptionApi->get_process_description:\n")
        pprint(api_response)
    except Exception as e:
        print("Exception when calling ProcessDescriptionApi->get_process_description: %s\n" % e)
```



### Parameters


Name | Type | Description  | Notes
------------- | ------------- | ------------- | -------------
 **process_id** | **str**| local identifier of a process | 

### Return type

[**Process**](Process.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

### HTTP response details

| Status code | Description | Response headers |
|-------------|-------------|------------------|
**200** | A process description. |  -  |
**404** | The requested URI was not found. |  -  |

[[Back to top]](#) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to Model list]](../README.md#documentation-for-models) [[Back to README]](../README.md)

