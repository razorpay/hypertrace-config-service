package org.hypertrace.span.processing.config.service;

import com.google.inject.Inject;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.hypertrace.config.objectstore.ContextualConfigObject;
import org.hypertrace.core.grpcutils.context.RequestContext;
import org.hypertrace.span.processing.config.service.store.ExcludeSpanRulesConfigStore;
import org.hypertrace.span.processing.config.service.utils.TimestampConverter;
import org.hypertrace.span.processing.config.service.v1.CreateExcludeSpanRuleRequest;
import org.hypertrace.span.processing.config.service.v1.CreateExcludeSpanRuleResponse;
import org.hypertrace.span.processing.config.service.v1.DeleteExcludeSpanRuleRequest;
import org.hypertrace.span.processing.config.service.v1.DeleteExcludeSpanRuleResponse;
import org.hypertrace.span.processing.config.service.v1.ExcludeSpanRule;
import org.hypertrace.span.processing.config.service.v1.ExcludeSpanRuleDetails;
import org.hypertrace.span.processing.config.service.v1.ExcludeSpanRuleInfo;
import org.hypertrace.span.processing.config.service.v1.ExcludeSpanRuleMetadata;
import org.hypertrace.span.processing.config.service.v1.GetAllExcludeSpanRulesRequest;
import org.hypertrace.span.processing.config.service.v1.GetAllExcludeSpanRulesResponse;
import org.hypertrace.span.processing.config.service.v1.SpanProcessingConfigServiceGrpc;
import org.hypertrace.span.processing.config.service.v1.UpdateExcludeSpanRule;
import org.hypertrace.span.processing.config.service.v1.UpdateExcludeSpanRuleRequest;
import org.hypertrace.span.processing.config.service.v1.UpdateExcludeSpanRuleResponse;
import org.hypertrace.span.processing.config.service.validation.SpanProcessingConfigRequestValidator;

@Slf4j
class SpanProcessingConfigServiceImpl
    extends SpanProcessingConfigServiceGrpc.SpanProcessingConfigServiceImplBase {
  private final SpanProcessingConfigRequestValidator validator;
  private final ExcludeSpanRulesConfigStore ruleStore;
  private final TimestampConverter timestampConverter;

  @Inject
  SpanProcessingConfigServiceImpl(
      ExcludeSpanRulesConfigStore ruleStore,
      SpanProcessingConfigRequestValidator requestValidator,
      TimestampConverter timestampConverter) {
    this.validator = requestValidator;
    this.ruleStore = ruleStore;
    this.timestampConverter = timestampConverter;
  }

  @Override
  public void getAllExcludeSpanRules(
      GetAllExcludeSpanRulesRequest request,
      StreamObserver<GetAllExcludeSpanRulesResponse> responseObserver) {
    try {
      RequestContext requestContext = RequestContext.CURRENT.get();
      this.validator.validateOrThrow(requestContext, request);

      responseObserver.onNext(
          GetAllExcludeSpanRulesResponse.newBuilder()
              .addAllRuleDetails(ruleStore.getAllData(requestContext))
              .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error("Unable to get all exclude span rules for request: {}", request, e);
      responseObserver.onError(e);
    }
  }

  @Override
  public void createExcludeSpanRule(
      CreateExcludeSpanRuleRequest request,
      StreamObserver<CreateExcludeSpanRuleResponse> responseObserver) {
    try {
      RequestContext requestContext = RequestContext.CURRENT.get();
      this.validator.validateOrThrow(requestContext, request);

      // TODO: need to handle priorities
      ExcludeSpanRule newRule =
          ExcludeSpanRule.newBuilder()
              .setId(UUID.randomUUID().toString())
              .setRuleInfo(request.getRuleInfo())
              .build();

      responseObserver.onNext(
          CreateExcludeSpanRuleResponse.newBuilder()
              .setRuleDetails(
                  buildExcludeSpanRuleDetails(this.ruleStore.upsertObject(requestContext, newRule)))
              .build());
      responseObserver.onCompleted();
    } catch (Exception exception) {
      log.error("Error creating exclude span rule {}", request, exception);
      responseObserver.onError(exception);
    }
  }

  @Override
  public void updateExcludeSpanRule(
      UpdateExcludeSpanRuleRequest request,
      StreamObserver<UpdateExcludeSpanRuleResponse> responseObserver) {
    try {
      RequestContext requestContext = RequestContext.CURRENT.get();
      this.validator.validateOrThrow(requestContext, request);

      UpdateExcludeSpanRule updateExcludeSpanRule = request.getRule();
      ExcludeSpanRule existingRule =
          this.ruleStore
              .getData(requestContext, updateExcludeSpanRule.getId())
              .orElseThrow(Status.NOT_FOUND::asException);
      ExcludeSpanRule updatedRule = buildUpdatedRule(existingRule, updateExcludeSpanRule);

      responseObserver.onNext(
          UpdateExcludeSpanRuleResponse.newBuilder()
              .setRuleDetails(
                  buildExcludeSpanRuleDetails(
                      this.ruleStore.upsertObject(requestContext, updatedRule)))
              .build());
      responseObserver.onCompleted();
    } catch (Exception exception) {
      log.error("Error updating exclude span rule: {}", request, exception);
      responseObserver.onError(exception);
    }
  }

  @Override
  public void deleteExcludeSpanRule(
      DeleteExcludeSpanRuleRequest request,
      StreamObserver<DeleteExcludeSpanRuleResponse> responseObserver) {
    try {
      RequestContext requestContext = RequestContext.CURRENT.get();
      this.validator.validateOrThrow(requestContext, request);

      // TODO: need to handle priorities
      this.ruleStore
          .deleteObject(requestContext, request.getId())
          .orElseThrow(Status.NOT_FOUND::asRuntimeException);

      responseObserver.onNext(DeleteExcludeSpanRuleResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (Exception exception) {
      log.error("Error deleting exclude span rule: {}", request, exception);
      responseObserver.onError(exception);
    }
  }

  private ExcludeSpanRule buildUpdatedRule(
      ExcludeSpanRule existingRule, UpdateExcludeSpanRule updateExcludeSpanRule) {
    return ExcludeSpanRule.newBuilder(existingRule)
        .setRuleInfo(
            ExcludeSpanRuleInfo.newBuilder()
                .setName(updateExcludeSpanRule.getName())
                .setFilter(updateExcludeSpanRule.getFilter())
                .setDisabled(updateExcludeSpanRule.getDisabled())
                .build())
        .build();
  }

  private ExcludeSpanRuleDetails buildExcludeSpanRuleDetails(
      ContextualConfigObject<ExcludeSpanRule> configObject) {
    return ExcludeSpanRuleDetails.newBuilder()
        .setRule(configObject.getData())
        .setMetadata(
            ExcludeSpanRuleMetadata.newBuilder()
                .setCreationTimestamp(
                    timestampConverter.convert(configObject.getCreationTimestamp()))
                .setLastUpdatedTimestamp(
                    timestampConverter.convert(configObject.getLastUpdatedTimestamp()))
                .build())
        .build();
  }
}