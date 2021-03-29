/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2020 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.serving.service;

import static feast.common.models.FeatureTable.getFeatureTableStringRef;

import com.google.protobuf.Duration;
import com.google.protobuf.ProtocolStringList;
import feast.common.models.FeatureV2;
import feast.proto.core.FeatureTableProto;
import feast.proto.serving.ServingAPIProto.FeastServingType;
import feast.proto.serving.ServingAPIProto.FeatureReferenceV2;
import feast.proto.serving.ServingAPIProto.GetFeastServingInfoRequest;
import feast.proto.serving.ServingAPIProto.GetFeastServingInfoResponse;
import feast.proto.serving.ServingAPIProto.GetOnlineFeaturesRequestV2;
import feast.proto.serving.ServingAPIProto.GetOnlineFeaturesResponse;
import feast.proto.types.ValueProto;
import feast.serving.exception.SpecRetrievalException;
import feast.serving.specs.CachedSpecService;
import feast.serving.util.Metrics;
import feast.storage.api.retriever.Feature;
import feast.storage.api.retriever.OnlineRetrieverV2;
import io.grpc.Status;
import io.opentracing.Span;
import io.opentracing.Tracer;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;

public class OnlineServingServiceV2 implements ServingServiceV2 {

  private static final Logger log = org.slf4j.LoggerFactory.getLogger(OnlineServingServiceV2.class);
  private final CachedSpecService specService;
  private final Tracer tracer;
  private final OnlineRetrieverV2 retriever;

  public OnlineServingServiceV2(
      OnlineRetrieverV2 retriever, CachedSpecService specService, Tracer tracer) {
    this.retriever = retriever;
    this.specService = specService;
    this.tracer = tracer;
  }

  /** {@inheritDoc} */
  @Override
  public GetFeastServingInfoResponse getFeastServingInfo(
      GetFeastServingInfoRequest getFeastServingInfoRequest) {
    return GetFeastServingInfoResponse.newBuilder()
        .setType(FeastServingType.FEAST_SERVING_TYPE_ONLINE)
        .build();
  }

  @Override
  public GetOnlineFeaturesResponse getOnlineFeatures(GetOnlineFeaturesRequestV2 request) {
    String projectName = request.getProject();
    List<FeatureReferenceV2> featureReferences = request.getFeaturesList();

    // Autofill default project if project is not specified
    if (projectName.isEmpty()) {
      projectName = "default";
    }

    List<GetOnlineFeaturesRequestV2.EntityRow> entityRows = request.getEntityRowsList();
    List<Map<String, ValueProto.Value>> values =
        entityRows.stream().map(r -> new HashMap<>(r.getFieldsMap())).collect(Collectors.toList());
    List<Map<String, GetOnlineFeaturesResponse.FieldStatus>> statuses =
        entityRows.stream()
            .map(
                r ->
                    r.getFieldsMap().entrySet().stream()
                        .map(
                            entry ->
                                Pair.of(
                                    entry.getKey(),
                                    // TODO: Is there a better default value to set
                                    getMetadata(
                                        ValueProto.Value.newBuilder().build(),
                                        true,
                                        false)))
                        .collect(Collectors.toMap(Pair::getLeft, Pair::getRight)))
            .collect(Collectors.toList());

    String finalProjectName = projectName;
    Map<FeatureReferenceV2, Duration> featureMaxAges =
        featureReferences.stream()
            .distinct()
            .collect(
                Collectors.toMap(
                    Function.identity(),
                    ref -> specService.getFeatureTableSpec(finalProjectName, ref).getMaxAge()));
    List<String> entityNames = featureReferences.stream().map(
        ref -> specService.getFeatureTableSpec(finalProjectName, ref).getEntitiesList()
    ).findFirst().get();

    Map<FeatureReferenceV2, ValueProto.ValueType.Enum> featureValueTypes =
        featureReferences.stream()
            .distinct()
            .collect(
                Collectors.toMap(
                    Function.identity(),
                    ref -> {
                      try {
                        return specService.getFeatureSpec(finalProjectName, ref).getValueType();
                      } catch (SpecRetrievalException e) {
                        return ValueProto.ValueType.Enum.INVALID;
                      }
                    }));

    Span storageRetrievalSpan = tracer.buildSpan("storageRetrieval").start();
    if (storageRetrievalSpan != null) {
      storageRetrievalSpan.setTag("entities", entityRows.size());
      storageRetrievalSpan.setTag("features", featureReferences.size());
    }
    List<List<Feature>> entityRowsFeatures =
        retriever.getOnlineFeatures(projectName, entityRows, featureReferences, entityNames);
    if (storageRetrievalSpan != null) {
      storageRetrievalSpan.finish();
    }

    if (entityRowsFeatures.size() != entityRows.size()) {
      throw Status.INTERNAL
          .withDescription(
              "The no. of FeatureRow obtained from OnlineRetriever"
                  + "does not match no. of entityRow passed.")
          .asRuntimeException();
    }

    Span postProcessingSpan = tracer.buildSpan("postProcessing").start();

    for (int i = 0; i < entityRows.size(); i++) {
      GetOnlineFeaturesRequestV2.EntityRow entityRow = entityRows.get(i);
      List<Feature> curEntityRowFeatures = entityRowsFeatures.get(i);

      Map<FeatureReferenceV2, Feature> featureReferenceFeatureMap =
          getFeatureRefFeatureMap(curEntityRowFeatures);

      Map<String, ValueProto.Value> rowValues = values.get(i);
      Map<String, GetOnlineFeaturesResponse.FieldStatus> rowStatuses = statuses.get(i);

      for (FeatureReferenceV2 featureReference : featureReferences) {
        if (featureReferenceFeatureMap.containsKey(featureReference)) {
          Feature feature = featureReferenceFeatureMap.get(featureReference);

          ValueProto.Value value =
              feature.getFeatureValue(featureValueTypes.get(feature.getFeatureReference()));
          Boolean isFound =
              feature.isSameFeatureSpec(featureValueTypes.get(feature.getFeatureReference()));

          boolean isOutsideMaxAge =
              checkOutsideMaxAge(
                  feature, entityRow, featureMaxAges.get(feature.getFeatureReference()));

          if (!isOutsideMaxAge && value != null) {
            rowValues.put(FeatureV2.getFeatureStringRef(feature.getFeatureReference()), value);
          } else {
            rowValues.put(
                FeatureV2.getFeatureStringRef(feature.getFeatureReference()),
                ValueProto.Value.newBuilder().build());
          }

          rowStatuses.put(
              FeatureV2.getFeatureStringRef(feature.getFeatureReference()),
              getMetadata(value, !isFound, isOutsideMaxAge));
        } else {
          rowValues.put(
              FeatureV2.getFeatureStringRef(featureReference),
              ValueProto.Value.newBuilder().build());

          rowStatuses.put(
              FeatureV2.getFeatureStringRef(featureReference), getMetadata(null, true, false));
        }
      }
      // Populate metrics/log request
      populateCountMetrics(rowStatuses, projectName);
    }

    if (postProcessingSpan != null) {
      postProcessingSpan.finish();
    }

    populateHistogramMetrics(entityRows, featureReferences, projectName);
    populateFeatureCountMetrics(featureReferences, projectName);

    // Build response field values from entityValuesMap and entityStatusesMap
    // Response field values should be in the same order as the entityRows provided by the user.
    List<GetOnlineFeaturesResponse.FieldValues> fieldValuesList =
        IntStream.range(0, entityRows.size())
            .mapToObj(
                entityRowIdx ->
                    GetOnlineFeaturesResponse.FieldValues.newBuilder()
                        .putAllFields(values.get(entityRowIdx))
                        .putAllStatuses(statuses.get(entityRowIdx))
                        .build())
            .collect(Collectors.toList());
    return GetOnlineFeaturesResponse.newBuilder().addAllFieldValues(fieldValuesList).build();
  }

  private static Map<FeatureReferenceV2, Feature> getFeatureRefFeatureMap(List<Feature> features) {
    return features.stream()
        .collect(Collectors.toMap(Feature::getFeatureReference, Function.identity()));
  }

  /**
   * Generate Field level Status metadata for the given valueMap.
   *
   * @param value value to generate metadata for.
   * @param isOutsideMaxAge whether the given valueMap contains values with age outside
   *     FeatureTable's max age.
   * @return a 1:1 map keyed by field name containing field status metadata instead of values in the
   *     given valueMap.
   */
  private static GetOnlineFeaturesResponse.FieldStatus getMetadata(
      ValueProto.Value value, boolean isNotFound, boolean isOutsideMaxAge) {

    if (isNotFound) {
      return GetOnlineFeaturesResponse.FieldStatus.NOT_FOUND;
    } else if (isOutsideMaxAge) {
      return GetOnlineFeaturesResponse.FieldStatus.OUTSIDE_MAX_AGE;
    } else if (value == null || value.getValCase().equals(ValueProto.Value.ValCase.VAL_NOT_SET)) {
      return GetOnlineFeaturesResponse.FieldStatus.NULL_VALUE;
    }
    return GetOnlineFeaturesResponse.FieldStatus.PRESENT;
  }

  /**
   * Determine if the feature data in the given feature row is outside maxAge. Data is outside
   * maxAge to be when the difference ingestion time set in feature row and the retrieval time set
   * in entity row exceeds FeatureTable max age.
   *
   * @param feature contains the ingestion timing and feature data.
   * @param entityRow contains the retrieval timing of when features are pulled.
   * @param maxAge feature's max age.
   */
  private static boolean checkOutsideMaxAge(
      Feature feature, GetOnlineFeaturesRequestV2.EntityRow entityRow, Duration maxAge) {

    if (maxAge.equals(Duration.getDefaultInstance())) { // max age is not set
      return false;
    }

    long givenTimestamp = entityRow.getTimestamp().getSeconds();
    if (givenTimestamp == 0) {
      givenTimestamp = System.currentTimeMillis() / 1000;
    }
    long timeDifference = givenTimestamp - feature.getEventTimestamp().getSeconds();
    return timeDifference > maxAge.getSeconds();
  }

  /**
   * Populate histogram metrics that can be used for analysing online retrieval calls
   *
   * @param entityRows entity rows provided in request
   * @param featureReferences feature references provided in request
   * @param project project name provided in request
   */
  private void populateHistogramMetrics(
      List<GetOnlineFeaturesRequestV2.EntityRow> entityRows,
      List<FeatureReferenceV2> featureReferences,
      String project) {
    Metrics.requestEntityCountDistribution
        .labels(project)
        .observe(Double.valueOf(entityRows.size()));
    Metrics.requestFeatureCountDistribution
        .labels(project)
        .observe(Double.valueOf(featureReferences.size()));

    long countDistinctFeatureTables =
        featureReferences.stream()
            .map(featureReference -> getFeatureTableStringRef(project, featureReference))
            .distinct()
            .count();
    Metrics.requestFeatureTableCountDistribution
        .labels(project)
        .observe(Double.valueOf(countDistinctFeatureTables));
  }

  /**
   * Populate count metrics that can be used for analysing online retrieval calls
   *
   * @param statusMap Statuses of features which have been requested
   * @param project Project where request for features was called from
   */
  private void populateCountMetrics(
      Map<String, GetOnlineFeaturesResponse.FieldStatus> statusMap, String project) {
    statusMap.forEach(
        (featureRefString, status) -> {
          if (status == GetOnlineFeaturesResponse.FieldStatus.NOT_FOUND) {
            Metrics.notFoundKeyCount.labels(project, featureRefString).inc();
          }
          if (status == GetOnlineFeaturesResponse.FieldStatus.OUTSIDE_MAX_AGE) {
            Metrics.staleKeyCount.labels(project, featureRefString).inc();
          }
        });
  }

  private void populateFeatureCountMetrics(
      List<FeatureReferenceV2> featureReferences, String project) {
    featureReferences.forEach(
        featureReference ->
            Metrics.requestFeatureCount
                .labels(project, FeatureV2.getFeatureStringRef(featureReference))
                .inc());
  }
}
