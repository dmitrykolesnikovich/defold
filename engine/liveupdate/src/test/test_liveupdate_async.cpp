// Copyright 2020-2022 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#define JC_TEST_IMPLEMENTATION
#include <jc_test/jc_test.h>
#include <stdint.h>
#include <stdlib.h>
#include <dlib/log.h>
#include <dlib/time.h>
#include <resource/resource.h>
#include "../liveupdate.h"
#include "../liveupdate_private.h"


static volatile bool g_TestAsyncCallbackComplete = false;
static dmResource::HFactory g_ResourceFactory = 0x0;

class LiveUpdate : public jc_test_base_class
{
public:
    virtual void SetUp()
    {
        dmResource::NewFactoryParams params;
        params.m_MaxResources = 16;
        params.m_Flags = RESOURCE_FACTORY_FLAGS_RELOAD_SUPPORT;
        g_ResourceFactory = dmResource::NewFactory(&params, ".");
        ASSERT_NE((void*) 0, g_ResourceFactory);
    }
    virtual void TearDown()
    {
        if (g_ResourceFactory != NULL)
        {
            dmResource::DeleteFactory(g_ResourceFactory);
        }
    }
};

struct StoreResourceCallbackData
{
    void*           m_Callback;
    int             m_ResourceRef;
    int             m_HexDigestRef;
    const char*     m_HexDigest;
};


namespace dmLiveUpdate
{
    dmLiveUpdate::Result StoreZipArchive(const char* path, bool verify_archive)
    {
        return dmLiveUpdate::RESULT_OK;
    }

    dmLiveUpdate::Result NewArchiveIndexWithResource(const dmResource::Manifest* manifest, const char* expected_digest, const uint32_t expected_digest_length, const dmResourceArchive::LiveUpdateResource* resource, dmResourceArchive::HArchiveIndex& out_new_index)
    {
        out_new_index = (dmResourceArchive::HArchiveIndex) 0x5678;
        assert(manifest->m_ArchiveIndex == (dmResourceArchive::HArchiveIndexContainer) 0x1234);
        assert(strcmp("DUMMY2", expected_digest)==0);
        assert(expected_digest_length == 6);
        assert(*((uint32_t*)resource->m_Data) == 0xdeadbeef);
        return dmLiveUpdate::RESULT_OK;
    }

    void SetNewArchiveIndex(dmResourceArchive::HArchiveIndexContainer archive_container, dmResourceArchive::HArchiveIndex new_index, bool mem_mapped)
    {
        ASSERT_EQ((dmResourceArchive::HArchiveIndexContainer) 0x1234, archive_container);
        ASSERT_EQ((dmResourceArchive::HArchiveIndex) 0x5678, new_index);
        ASSERT_TRUE(mem_mapped);
    }

    void SetNewManifest(dmResource::Manifest* manifest)
    {
        ASSERT_TRUE(0 != manifest);
    }


    Result VerifyManifest(dmResource::Manifest* manifest)
    {
        return RESULT_OK;
    }


    Result ParseManifestBin(uint8_t* manifest_data, size_t manifest_len, dmResource::Manifest* manifest)
    {
        return RESULT_OK;
    }
}

static void Callback_StoreResource(bool status, void* ctx)
{
    StoreResourceCallbackData* callback_data = (StoreResourceCallbackData*)ctx;
    g_TestAsyncCallbackComplete = true;
    ASSERT_EQ((void*)(uintptr_t)1, callback_data->m_Callback);
    ASSERT_EQ(2, callback_data->m_ResourceRef);
    ASSERT_EQ(3, callback_data->m_HexDigestRef);
    ASSERT_STREQ("DUMMY1", callback_data->m_HexDigest);
    ASSERT_TRUE(status);
}

static void Callback_StoreResourceInvalidHeader(bool status, void* ctx)
{
    StoreResourceCallbackData* callback_data = (StoreResourceCallbackData*)ctx;
    g_TestAsyncCallbackComplete = true;
    ASSERT_EQ((void*)(uintptr_t)1, callback_data->m_Callback);
    ASSERT_EQ(2, callback_data->m_ResourceRef);
    ASSERT_EQ(3, callback_data->m_HexDigestRef);
    ASSERT_STREQ("DUMMY1", callback_data->m_HexDigest);
    ASSERT_FALSE(status);
}

TEST_F(LiveUpdate, TestAsync)
{
    dmLiveUpdate::AsyncInitialize(g_ResourceFactory);

    uint8_t buf[sizeof(dmResourceArchive::LiveUpdateResourceHeader)+sizeof(uint32_t)];
    const size_t buf_len = sizeof(buf);
    *((uint32_t*)&buf[sizeof(dmResourceArchive::LiveUpdateResourceHeader)]) = 0xdeadbeef;
    dmResourceArchive::LiveUpdateResource resource((const uint8_t*) buf, buf_len);

    StoreResourceCallbackData cb;
    cb.m_Callback = (void*)(uintptr_t)1;
    cb.m_ResourceRef = 2;
    cb.m_HexDigestRef = 3;
    cb.m_HexDigest = "DUMMY1";;

    dmResource::Manifest manifest;
    manifest.m_ArchiveIndex = (dmResourceArchive::HArchiveIndexContainer) 0x1234;

    dmLiveUpdate::AsyncResourceRequest request;
    request.m_Manifest = &manifest;
    request.m_ExpectedResourceDigestLength = 6;
    request.m_ExpectedResourceDigest = "DUMMY2";
    request.m_Resource.Set(resource);
    request.m_CallbackData = (void*)&cb;
    request.m_Callback = Callback_StoreResource;

    ASSERT_TRUE(dmLiveUpdate::AddAsyncResourceRequest(request));
    while(!g_TestAsyncCallbackComplete)
    {
        dmLiveUpdate::AsyncUpdate();

        dmTime::Sleep(1000);
    }

    dmLiveUpdate::AsyncFinalize();
}

TEST_F(LiveUpdate, TestAsyncInvalidResource)
{
    dmLiveUpdate::AsyncInitialize(g_ResourceFactory);

    uint8_t buf[sizeof(dmResourceArchive::LiveUpdateResourceHeader)+sizeof(uint32_t)];
    const size_t buf_len = sizeof(buf);
    *((uint32_t*)&buf[sizeof(dmResourceArchive::LiveUpdateResourceHeader)]) = 0xdeadbeef;
    dmResourceArchive::LiveUpdateResource resource((const uint8_t*) buf, buf_len);

    resource.m_Header = 0x0;

    StoreResourceCallbackData cb;
    cb.m_Callback = (void*)(uintptr_t)1;
    cb.m_ResourceRef = 2;
    cb.m_HexDigestRef = 3;
    cb.m_HexDigest = "DUMMY1";;

    dmResource::Manifest manifest;
    manifest.m_ArchiveIndex = (dmResourceArchive::HArchiveIndexContainer) 0x1234;

    dmLiveUpdate::AsyncResourceRequest request;
    request.m_Manifest = &manifest;
    request.m_ExpectedResourceDigestLength = 6;
    request.m_ExpectedResourceDigest = "DUMMY2";
    request.m_Resource.Set(resource);
    request.m_CallbackData = (void*)&cb;
    request.m_Callback = Callback_StoreResourceInvalidHeader;

    ASSERT_TRUE(dmLiveUpdate::AddAsyncResourceRequest(request));
    while(!g_TestAsyncCallbackComplete)
    {
        dmLiveUpdate::AsyncUpdate();

        dmTime::Sleep(1000);
    }

    dmLiveUpdate::AsyncFinalize();
}


int main(int argc, char **argv)
{
    jc_test_init(&argc, argv);
    int ret = jc_test_run_all();
    return ret;
}
