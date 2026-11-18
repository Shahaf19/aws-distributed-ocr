package com.bgu.ocr.aws;

import com.bgu.ocr.Config;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Finds, launches, tags and terminates our instances. EC2 API calls are not billed; instance time is. */
public final class Ec2Helper {

    private final Ec2Client ec2;

    public Ec2Helper(Ec2Client ec2) {
        this.ec2 = ec2;
    }

    /** Uses the SDK's default credentials: the instance profile on EC2, ~/.aws/credentials locally. */
    public static Ec2Helper withDefaultClient() {
        return new Ec2Helper(Ec2Client.builder().region(Region.of(Config.REGION)).build());
    }

    /** Our instances with this role tag (Managers or Workers), in every state except terminated. */
    public List<InstanceInfo> instancesWithRole(String role) {
        return describe(Filter.builder().name("tag:" + Ec2Tags.ROLE).values(role).build(),
                Filter.builder().name("instance-state-name")
                        .values("pending", "running", "shutting-down", "stopping", "stopped").build());
    }

    /** Every booting or running instance in the account: the 19-instance cap is an account limit. */
    public int aliveInstanceCount() {
        return describe(Filter.builder().name("instance-state-name").values("pending", "running").build()).size();
    }

    /**
     * Launches up to {@code count} instances from our AMI with the instance profile (no credentials shipped), the
     * boot script, and the tags applied in the same call (so they're findable from the first moment).
     *
     * @return the ids of the instances actually launched
     */
    public List<String> launch(int count, String userData, Map<String, String> tags) {
        List<Tag> tagList = tags.entrySet().stream()
                .map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build())
                .toList();
        String encoded = Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));
        return ec2.runInstances(b -> b
                        .imageId(Config.AMI_ID)
                        .instanceType(Config.INSTANCE_TYPE)
                        .minCount(1)
                        .maxCount(count)
                        .iamInstanceProfile(p -> p.name(Config.INSTANCE_PROFILE))
                        .userData(encoded)
                        .tagSpecifications(TagSpecification.builder()
                                .resourceType(ResourceType.INSTANCE).tags(tagList).build()))
                .instances().stream()
                .map(Instance::instanceId)
                .toList();
    }

    /** One instance by id, or empty if EC2 doesn't know it (e.g. a local test id). */
    public Optional<InstanceInfo> instance(String instanceId) {
        return describe(Filter.builder().name("instance-id").values(instanceId).build()).stream().findFirst();
    }

    public void tag(String instanceId, String key, String value) {
        tagAll(List.of(instanceId), Map.of(key, value));
    }

    /** Applies all tags to all instances in one call. */
    public void tagAll(Collection<String> instanceIds, Map<String, String> tags) {
        if (instanceIds.isEmpty()) {
            return;
        }
        List<Tag> tagList = tags.entrySet().stream()
                .map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build())
                .toList();
        ec2.createTags(b -> b.resources(instanceIds).tags(tagList));
    }

    public void terminate(Collection<String> instanceIds) {
        if (!instanceIds.isEmpty()) {
            ec2.terminateInstances(b -> b.instanceIds(instanceIds));
        }
    }

    private List<InstanceInfo> describe(Filter... filters) {
        List<InstanceInfo> found = new ArrayList<>();
        ec2.describeInstancesPaginator(b -> b.filters(filters)).reservations()
                .forEach(reservation -> reservation.instances().forEach(i -> found.add(toInfo(i))));
        return found;
    }

    private static InstanceInfo toInfo(Instance instance) {
        Map<String, String> tags = instance.tags().stream().collect(Collectors.toMap(Tag::key, Tag::value));
        return new InstanceInfo(instance.instanceId(), instance.state().nameAsString(), tags);
    }
}
