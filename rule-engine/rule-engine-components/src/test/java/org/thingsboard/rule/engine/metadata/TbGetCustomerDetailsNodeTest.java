/**
 * Copyright © 2016-2023 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.rule.engine.metadata;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.ListeningExecutor;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.util.EntityDetails;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityView;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DashboardId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityViewId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.dao.asset.AssetService;
import org.thingsboard.server.dao.customer.CustomerService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.edge.EdgeService;
import org.thingsboard.server.dao.entityview.EntityViewService;
import org.thingsboard.server.dao.user.UserService;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TbGetCustomerDetailsNodeTest {

    private static final DeviceId DUMMY_DEVICE_ORIGINATOR = new DeviceId(UUID.randomUUID());
    private static final TenantId TENANT_ID = new TenantId(UUID.randomUUID());
    private static final ListeningExecutor DB_EXECUTOR = new ListeningExecutor() {
        @Override
        public <T> ListenableFuture<T> executeAsync(Callable<T> task) {
            try {
                return Futures.immediateFuture(task.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void execute(@NotNull Runnable command) {
            command.run();
        }
    };
    @Mock
    private TbContext ctxMock;
    @Mock
    private CustomerService customerServiceMock;
    @Mock
    private DeviceService deviceServiceMock;
    @Mock
    private AssetService assetServiceMock;
    @Mock
    private EntityViewService entityViewServiceMock;
    @Mock
    private UserService userServiceMock;
    @Mock
    private EdgeService edgeServiceMock;
    private TbGetCustomerDetailsNode node;
    private TbGetCustomerDetailsNodeConfiguration config;
    private TbNodeConfiguration nodeConfiguration;
    private TbMsg msg;
    private Customer customer;

    @BeforeEach
    public void setUp() {
        node = new TbGetCustomerDetailsNode();
        config = new TbGetCustomerDetailsNodeConfiguration().defaultConfiguration();
        nodeConfiguration = new TbNodeConfiguration(JacksonUtil.valueToTree(config));
        customer = new Customer();
        customer.setId(new CustomerId(UUID.randomUUID()));
        customer.setTitle("Customer title");
        customer.setCountry("Customer country");
        customer.setCity("Customer city");
        customer.setState("Customer state");
        customer.setZip("123456");
        customer.setAddress("Customer address 1");
        customer.setAddress2("Customer address 2");
        customer.setPhone("+123456789");
        customer.setEmail("email@tenant.com");
        customer.setAdditionalInfo(JacksonUtil.toJsonNode("{\"someProperty\":\"someValue\",\"description\":\"Customer description\"}"));
    }

    @Test
    public void givenConfigWithNullFetchTo_whenInit_thenException() {
        // GIVEN
        config.setDetailsList(List.of(EntityDetails.ID));
        config.setFetchTo(null);
        nodeConfiguration = new TbNodeConfiguration(JacksonUtil.valueToTree(config));

        // WHEN
        var exception = assertThrows(TbNodeException.class, () -> node.init(ctxMock, nodeConfiguration));

        // THEN
        assertThat(exception.getMessage()).isEqualTo("FetchTo cannot be null!");
        verify(ctxMock, never()).tellSuccess(any());
    }

    @Test
    public void givenNoEntityDetailsSelected_whenInit_thenException() {
        // GIVEN
        var expectedExceptionMessage = "No entity details selected!";

        config.setDetailsList(Collections.emptyList());
        nodeConfiguration = new TbNodeConfiguration(JacksonUtil.valueToTree(config));

        // WHEN
        var exception = assertThrows(TbNodeException.class, () -> node.init(ctxMock, nodeConfiguration));

        // THEN
        assertThat(exception.getMessage()).isEqualTo(expectedExceptionMessage);
        verify(ctxMock, never()).tellSuccess(any());
    }

    @Test
    public void givenDefaultConfig_whenInit_thenOK() {
        assertThat(config.getDetailsList()).isEqualTo(Collections.emptyList());
        assertThat(config.getFetchTo()).isEqualTo(FetchTo.DATA);
    }

    @Test
    public void givenCustomConfig_whenInit_thenOK() throws TbNodeException {
        // GIVEN
        config.setDetailsList(List.of(EntityDetails.ID, EntityDetails.PHONE));
        config.setFetchTo(FetchTo.METADATA);
        nodeConfiguration = new TbNodeConfiguration(JacksonUtil.valueToTree(config));

        // WHEN
        node.init(ctxMock, nodeConfiguration);

        // THEN
        assertThat(node.config).isEqualTo(config);
        assertThat(config.getDetailsList()).isEqualTo(List.of(EntityDetails.ID, EntityDetails.PHONE));
        assertThat(config.getFetchTo()).isEqualTo(FetchTo.METADATA);
        assertThat(node.fetchTo).isEqualTo(FetchTo.METADATA);
    }

    @Test
    public void givenMsgDataIsNotAnJsonObjectAndFetchToData_whenOnMsg_thenException() {
        // GIVEN
        node.fetchTo = FetchTo.DATA;
        msg = TbMsg.newMsg("SOME_MESSAGE_TYPE", DUMMY_DEVICE_ORIGINATOR, new TbMsgMetaData(), "[]");

        // WHEN
        var exception = assertThrows(IllegalArgumentException.class, () -> node.onMsg(ctxMock, msg));

        // THEN
        assertThat(exception.getMessage()).isEqualTo("Message body is not an object!");
        verify(ctxMock, never()).tellSuccess(any());
    }

    @Test
    public void givenEntityThatDoesNotBelongToTheCurrentTenant_whenOnMsg_thenException() {
        // GIVEN
        var expectedExceptionMessage = "Entity with id: '" + DUMMY_DEVICE_ORIGINATOR +
                "' specified in the configuration doesn't belong to the current tenant.";

        doThrow(new RuntimeException(expectedExceptionMessage)).when(ctxMock).checkTenantEntity(DUMMY_DEVICE_ORIGINATOR);
        msg = TbMsg.newMsg("SOME_MESSAGE_TYPE", DUMMY_DEVICE_ORIGINATOR, new TbMsgMetaData(), "{}");

        // WHEN
        var exception = assertThrows(RuntimeException.class, () -> node.onMsg(ctxMock, msg));

        // THEN
        assertThat(exception.getMessage()).isEqualTo(expectedExceptionMessage);
        verify(ctxMock, never()).tellSuccess(any());
    }

    @Test
    public void givenAllEntityDetailsAndFetchToData_whenOnMsg_thenShouldTellSuccessAndFetchAllToData() {
        // GIVEN
        var device = new Device();
        device.setId(new DeviceId(UUID.randomUUID()));
        device.setCustomerId(customer.getId());

        prepareMsgAndConfig(FetchTo.DATA, List.of(EntityDetails.values()), device.getId());

        when(ctxMock.getDeviceService()).thenReturn(deviceServiceMock);
        when(deviceServiceMock.findDeviceByIdAsync(eq(TENANT_ID), eq(device.getId()))).thenReturn(Futures.immediateFuture(device));

        mockFindCustomer();

        when(ctxMock.getDbCallbackExecutor()).thenReturn(DB_EXECUTOR);

        // WHEN
        node.onMsg(ctxMock, msg);

        // THEN
        var actualMessageCaptor = ArgumentCaptor.forClass(TbMsg.class);

        verify(ctxMock, times(1)).tellSuccess(actualMessageCaptor.capture());
        verify(ctxMock, never()).tellFailure(any(), any());

        var expectedMsgData = "{\"dataKey1\":123,\"dataKey2\":\"dataValue2\"," +
                "\"customer_id\":\"" + customer.getId() + "\"," +
                "\"customer_title\":\"" + customer.getTitle() + "\"," +
                "\"customer_country\":\"" + customer.getCountry() + "\"," +
                "\"customer_city\":\"" + customer.getCity() + "\"," +
                "\"customer_state\":\"" + customer.getState() + "\"," +
                "\"customer_zip\":\"" + customer.getZip() + "\"," +
                "\"customer_address\":\"" + customer.getAddress() + "\"," +
                "\"customer_address2\":\"" + customer.getAddress2() + "\"," +
                "\"customer_phone\":\"" + customer.getPhone() + "\"," +
                "\"customer_email\":\"" + customer.getEmail() + "\"," +
                "\"customer_additionalInfo\":\"" + customer.getAdditionalInfo().get("description").asText() + "\"}";

        assertThat(actualMessageCaptor.getValue().getData()).isEqualTo(expectedMsgData);
        assertThat(actualMessageCaptor.getValue().getMetaData()).isEqualTo(msg.getMetaData());
    }

    @Test
    public void givenSomeEntityDetailsAndFetchToMetadata_whenOnMsg_thenShouldTellSuccessAndFetchSomeToMetaData() {
        // GIVEN
        var asset = new Asset();
        asset.setId(new AssetId(UUID.randomUUID()));
        asset.setCustomerId(customer.getId());

        prepareMsgAndConfig(FetchTo.METADATA, List.of(EntityDetails.ID, EntityDetails.TITLE, EntityDetails.PHONE), asset.getId());

        when(ctxMock.getAssetService()).thenReturn(assetServiceMock);
        when(assetServiceMock.findAssetByIdAsync(eq(TENANT_ID), eq(asset.getId()))).thenReturn(Futures.immediateFuture(asset));

        mockFindCustomer();

        when(ctxMock.getDbCallbackExecutor()).thenReturn(DB_EXECUTOR);

        // WHEN
        node.onMsg(ctxMock, msg);

        // THEN
        var actualMessageCaptor = ArgumentCaptor.forClass(TbMsg.class);

        verify(ctxMock, times(1)).tellSuccess(actualMessageCaptor.capture());
        verify(ctxMock, never()).tellFailure(any(), any());

        var expectedMsgMetaData = new TbMsgMetaData(msg.getMetaData().getData());
        expectedMsgMetaData.putValue("customer_id", customer.getId().getId().toString());
        expectedMsgMetaData.putValue("customer_title", customer.getTitle());
        expectedMsgMetaData.putValue("customer_phone", customer.getPhone());

        assertThat(actualMessageCaptor.getValue().getData()).isEqualTo(msg.getData());
        assertThat(actualMessageCaptor.getValue().getMetaData()).isEqualTo(expectedMsgMetaData);
    }

    @Test
    public void givenNoEntityDetailsAndFetchToMetadata_whenOnMsg_thenShouldTellSuccessAndFetchNothingToMetaData() {
        // GIVEN
        var entityView = new EntityView();
        entityView.setId(new EntityViewId(UUID.randomUUID()));
        entityView.setCustomerId(customer.getId());

        prepareMsgAndConfig(FetchTo.METADATA, Collections.emptyList(), entityView.getId());

        when(ctxMock.getEntityViewService()).thenReturn(entityViewServiceMock);
        when(entityViewServiceMock.findEntityViewByIdAsync(eq(TENANT_ID), eq(entityView.getId()))).thenReturn(Futures.immediateFuture(entityView));

        mockFindCustomer();

        when(ctxMock.getDbCallbackExecutor()).thenReturn(DB_EXECUTOR);

        // WHEN
        node.onMsg(ctxMock, msg);

        // THEN
        var actualMessageCaptor = ArgumentCaptor.forClass(TbMsg.class);

        verify(ctxMock, times(1)).tellSuccess(actualMessageCaptor.capture());
        verify(ctxMock, never()).tellFailure(any(), any());

        assertThat(actualMessageCaptor.getValue().getData()).isEqualTo(msg.getData());
        assertThat(actualMessageCaptor.getValue().getMetaData()).isEqualTo(msg.getMetaData());
    }

    @Test
    public void givenNotPresentEntityDetailsAndFetchToData_whenOnMsg_thenShouldTellSuccessAndFetchNothingToData() {
        // GIVEN
        customer.setZip(null);
        customer.setAddress(null);
        customer.setAddress2(null);

        var user = new User();
        user.setId(new UserId(UUID.randomUUID()));
        user.setCustomerId(customer.getId());

        prepareMsgAndConfig(FetchTo.DATA, List.of(EntityDetails.ZIP, EntityDetails.ADDRESS, EntityDetails.ADDRESS2), user.getId());

        when(ctxMock.getUserService()).thenReturn(userServiceMock);
        when(userServiceMock.findUserByIdAsync(eq(TENANT_ID), eq(user.getId()))).thenReturn(Futures.immediateFuture(user));

        when(ctxMock.getDbCallbackExecutor()).thenReturn(DB_EXECUTOR);

        mockFindCustomer();

        // WHEN
        node.onMsg(ctxMock, msg);

        // THEN
        var actualMessageCaptor = ArgumentCaptor.forClass(TbMsg.class);

        verify(ctxMock, times(1)).tellSuccess(actualMessageCaptor.capture());
        verify(ctxMock, never()).tellFailure(any(), any());

        assertThat(actualMessageCaptor.getValue().getData()).isEqualTo(msg.getData());
        assertThat(actualMessageCaptor.getValue().getMetaData()).isEqualTo(msg.getMetaData());
    }

    @Test
    public void givenDidNotFindCustomer_whenOnMsg_thenShouldTellSuccessAndFetchNothingToData() {
        // GIVEN
        var edge = new Edge();
        edge.setId(new EdgeId(UUID.randomUUID()));
        edge.setCustomerId(customer.getId());

        prepareMsgAndConfig(FetchTo.DATA, List.of(EntityDetails.ZIP, EntityDetails.ADDRESS, EntityDetails.ADDRESS2), edge.getId());

        when(ctxMock.getTenantId()).thenReturn(TENANT_ID);

        when(ctxMock.getEdgeService()).thenReturn(edgeServiceMock);
        when(edgeServiceMock.findEdgeByIdAsync(eq(TENANT_ID), eq(edge.getId()))).thenReturn(Futures.immediateFuture(edge));

        when(ctxMock.getCustomerService()).thenReturn(customerServiceMock);
        when(customerServiceMock.findCustomerByIdAsync(eq(TENANT_ID), eq(customer.getId()))).thenReturn(Futures.immediateFuture(null));

        when(ctxMock.getDbCallbackExecutor()).thenReturn(DB_EXECUTOR);

        // WHEN
        node.onMsg(ctxMock, msg);

        // THEN
        var actualMessageCaptor = ArgumentCaptor.forClass(TbMsg.class);

        verify(ctxMock, times(1)).tellSuccess(actualMessageCaptor.capture());
        verify(ctxMock, never()).tellFailure(any(), any());

        assertThat(actualMessageCaptor.getValue().getData()).isEqualTo(msg.getData());
        assertThat(actualMessageCaptor.getValue().getMetaData()).isEqualTo(msg.getMetaData());
    }

    @Test
    public void givenDidNotFindOriginator_whenOnMsg_thenShouldTellSuccessAndFetchNothingToData() {
        // GIVEN
        var edge = new Edge();
        edge.setId(new EdgeId(UUID.randomUUID()));
        edge.setCustomerId(customer.getId());

        prepareMsgAndConfig(FetchTo.DATA, List.of(EntityDetails.ZIP, EntityDetails.ADDRESS, EntityDetails.ADDRESS2), edge.getId());

        when(ctxMock.getTenantId()).thenReturn(TENANT_ID);

        when(ctxMock.getEdgeService()).thenReturn(edgeServiceMock);
        when(edgeServiceMock.findEdgeByIdAsync(eq(TENANT_ID), eq(edge.getId()))).thenReturn(Futures.immediateFuture(null));

        when(ctxMock.getDbCallbackExecutor()).thenReturn(DB_EXECUTOR);

        // WHEN
        node.onMsg(ctxMock, msg);

        // THEN
        var actualMessageCaptor = ArgumentCaptor.forClass(TbMsg.class);

        verify(ctxMock, times(1)).tellSuccess(actualMessageCaptor.capture());
        verify(ctxMock, never()).tellFailure(any(), any());

        assertThat(actualMessageCaptor.getValue().getData()).isEqualTo(msg.getData());
        assertThat(actualMessageCaptor.getValue().getMetaData()).isEqualTo(msg.getMetaData());
    }

    @Test
    public void givenOriginatorNotAssignedToCustomer_whenOnMsg_thenShouldTellFailureAndFetchNothingToData() {
        // GIVEN
        var device = new Device();
        device.setId(new DeviceId(UUID.randomUUID()));

        prepareMsgAndConfig(FetchTo.DATA, List.of(EntityDetails.ZIP, EntityDetails.ADDRESS, EntityDetails.ADDRESS2), device.getId());

        when(ctxMock.getTenantId()).thenReturn(TENANT_ID);

        when(ctxMock.getDeviceService()).thenReturn(deviceServiceMock);
        when(deviceServiceMock.findDeviceByIdAsync(eq(TENANT_ID), eq(device.getId()))).thenReturn(Futures.immediateFuture(device));

        when(ctxMock.getDbCallbackExecutor()).thenReturn(DB_EXECUTOR);

        // WHEN
        node.onMsg(ctxMock, msg);

        // THEN
        var actualMessageCaptor = ArgumentCaptor.forClass(TbMsg.class);

        verify(ctxMock, times(1)).tellFailure(actualMessageCaptor.capture(), any());
        verify(ctxMock, never()).tellSuccess(any());

        assertThat(actualMessageCaptor.getValue().getData()).isEqualTo(msg.getData());
        assertThat(actualMessageCaptor.getValue().getMetaData()).isEqualTo(msg.getMetaData());
    }

    @Test
    public void givenNullDescriptionAndAddInfoEntityDetails_whenOnMsg_thenShouldTellSuccessAndFetchNothingToData() {
        // GIVEN
        customer.setAdditionalInfo(JacksonUtil.toJsonNode("{\"someProperty\":\"someValue\",\"description\":null}"));

        var device = new Device();
        device.setId(new DeviceId(UUID.randomUUID()));
        device.setCustomerId(customer.getId());

        prepareMsgAndConfig(FetchTo.DATA, List.of(EntityDetails.ADDITIONAL_INFO), device.getId());

        when(ctxMock.getDeviceService()).thenReturn(deviceServiceMock);
        when(deviceServiceMock.findDeviceByIdAsync(eq(TENANT_ID), eq(device.getId()))).thenReturn(Futures.immediateFuture(device));

        when(ctxMock.getDbCallbackExecutor()).thenReturn(DB_EXECUTOR);

        mockFindCustomer();

        // WHEN
        node.onMsg(ctxMock, msg);

        // THEN
        var actualMessageCaptor = ArgumentCaptor.forClass(TbMsg.class);

        verify(ctxMock, times(1)).tellSuccess(actualMessageCaptor.capture());
        verify(ctxMock, never()).tellFailure(any(), any());

        assertThat(actualMessageCaptor.getValue().getData()).isEqualTo(msg.getData());
        assertThat(actualMessageCaptor.getValue().getMetaData()).isEqualTo(msg.getMetaData());
    }

    @Test
    public void givenUnsupportedEntityType_whenOnMsg_thenShouldTellFailureAndFetchNothingToMetaData() {
        // GIVEN
        var dashboard = new Dashboard();
        dashboard.setId(new DashboardId(UUID.randomUUID()));

        prepareMsgAndConfig(FetchTo.METADATA, List.of(EntityDetails.STATE), dashboard.getId());

        // WHEN
        node.onMsg(ctxMock, msg);

        // THEN
        var actualMessageCaptor = ArgumentCaptor.forClass(TbMsg.class);

        verify(ctxMock, times(1)).tellFailure(actualMessageCaptor.capture(), any());
        verify(ctxMock, never()).tellSuccess(any());

        assertThat(actualMessageCaptor.getValue().getData()).isEqualTo(msg.getData());
        assertThat(actualMessageCaptor.getValue().getMetaData()).isEqualTo(msg.getMetaData());
    }

    private void prepareMsgAndConfig(FetchTo fetchTo, List<EntityDetails> detailsList, EntityId originator) {
        config.setDetailsList(detailsList);
        config.setFetchTo(fetchTo);

        node.config = config;
        node.fetchTo = fetchTo;

        var msgMetaData = new TbMsgMetaData();
        msgMetaData.putValue("metaKey1", "metaValue1");
        msgMetaData.putValue("metaKey2", "metaValue2");

        var msgData = "{\"dataKey1\":123,\"dataKey2\":\"dataValue2\"}";

        msg = TbMsg.newMsg("POST_TELEMETRY_REQUEST", originator, msgMetaData, msgData);
    }

    private void mockFindCustomer() {
        when(ctxMock.getTenantId()).thenReturn(TENANT_ID);
        when(ctxMock.getCustomerService()).thenReturn(customerServiceMock);
        when(customerServiceMock.findCustomerByIdAsync(eq(TENANT_ID), eq(customer.getId()))).thenReturn(Futures.immediateFuture(customer));
    }

}
