package dev.keyedge.infra.constructs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.IMetric;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.amazon.awscdk.services.sns.Topic;
import software.constructs.Construct;

/** One SNS topic per severity and a factory for alarms that notify one of them. */
public final class SeverityAlarms extends Construct {
  private final Map<String, Topic> topics = new LinkedHashMap<>();

  public SeverityAlarms(Construct scope, String id, String prefix, List<String> severities) {
    super(scope, id);
    for (String s : severities) {
      topics.put(
          s, Topic.Builder.create(this, s).topicName(prefix + "-" + s).enforceSsl(true).build());
    }
  }

  public Alarm alarm(
      String id, String severity, IMetric metric, double threshold, int periods, String what) {
    Topic topic = topics.get(severity);
    if (topic == null) {
      throw new IllegalArgumentException("unknown severity " + severity);
    }
    var alarm =
        Alarm.Builder.create(this, id)
            .metric(metric)
            .threshold(threshold)
            .evaluationPeriods(periods)
            .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
            .treatMissingData(TreatMissingData.NOT_BREACHING)
            .alarmDescription("[" + severity + "] " + what)
            .build();
    alarm.addAlarmAction(new SnsAction(topic));
    return alarm;
  }

  public Map<String, Topic> topics() {
    return Map.copyOf(topics);
  }
}
