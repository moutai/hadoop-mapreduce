package org.apache.hadoop.yarn.api.impl.pb.service;

import org.apache.hadoop.yarn.api.ClientRMProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetClusterMetricsResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationIdResponse;
import org.apache.hadoop.yarn.api.protocolrecords.SubmitApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.FinishApplicationRequestPBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.FinishApplicationResponsePBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.GetApplicationMasterRequestPBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.GetApplicationMasterResponsePBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.GetClusterMetricsRequestPBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.GetClusterMetricsResponsePBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.GetNewApplicationIdRequestPBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.GetNewApplicationIdResponsePBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.SubmitApplicationRequestPBImpl;
import org.apache.hadoop.yarn.api.protocolrecords.impl.pb.SubmitApplicationResponsePBImpl;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.proto.ClientRMProtocol.ClientRMProtocolService.BlockingInterface;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.FinishApplicationRequestProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.FinishApplicationResponseProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.GetApplicationMasterRequestProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.GetApplicationMasterResponseProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.GetClusterMetricsRequestProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.GetClusterMetricsResponseProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.GetNewApplicationIdRequestProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.GetNewApplicationIdResponseProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.SubmitApplicationRequestProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.SubmitApplicationResponseProto;

import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;

public class ClientRMProtocolPBServiceImpl implements BlockingInterface {

  private ClientRMProtocol real;
  
  public ClientRMProtocolPBServiceImpl(ClientRMProtocol impl) {
    this.real = impl;
  }
  
  @Override
  public FinishApplicationResponseProto finishApplication(RpcController arg0,
      FinishApplicationRequestProto proto) throws ServiceException {
    FinishApplicationRequestPBImpl request = new FinishApplicationRequestPBImpl(proto);
    try {
      FinishApplicationResponse response = real.finishApplication(request);
      return ((FinishApplicationResponsePBImpl)response).getProto();
    } catch (YarnRemoteException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public GetApplicationMasterResponseProto getApplicationMaster(
      RpcController arg0, GetApplicationMasterRequestProto proto)
      throws ServiceException {
    GetApplicationMasterRequestPBImpl request = new GetApplicationMasterRequestPBImpl(proto);
    try {
      GetApplicationMasterResponse response = real.getApplicationMaster(request);
      return ((GetApplicationMasterResponsePBImpl)response).getProto();
    } catch (YarnRemoteException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public GetClusterMetricsResponseProto getClusterMetrics(RpcController arg0,
      GetClusterMetricsRequestProto proto) throws ServiceException {
    GetClusterMetricsRequestPBImpl request = new GetClusterMetricsRequestPBImpl(proto);
    try {
      GetClusterMetricsResponse response = real.getClusterMetrics(request);
      return ((GetClusterMetricsResponsePBImpl)response).getProto();
    } catch (YarnRemoteException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public GetNewApplicationIdResponseProto getNewApplicationId(
      RpcController arg0, GetNewApplicationIdRequestProto proto)
      throws ServiceException {
    GetNewApplicationIdRequestPBImpl request = new GetNewApplicationIdRequestPBImpl(proto);
    try {
      GetNewApplicationIdResponse response = real.getNewApplicationId(request);
      return ((GetNewApplicationIdResponsePBImpl)response).getProto();
    } catch (YarnRemoteException e) {
      throw new ServiceException(e);
    }
  }

  @Override
  public SubmitApplicationResponseProto submitApplication(RpcController arg0,
      SubmitApplicationRequestProto proto) throws ServiceException {
    SubmitApplicationRequestPBImpl request = new SubmitApplicationRequestPBImpl(proto);
    try {
      SubmitApplicationResponse response = real.submitApplication(request);
      return ((SubmitApplicationResponsePBImpl)response).getProto();
    } catch (YarnRemoteException e) {
      throw new ServiceException(e);
    }
  }

}
