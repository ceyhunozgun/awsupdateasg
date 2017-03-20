package ceyhunozgun.awsupdateasg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.event.ProgressListener;
import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder;
import com.amazonaws.services.autoscaling.model.AutoScalingGroup;
import com.amazonaws.services.autoscaling.model.AutoScalingInstanceDetails;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsResult;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingInstancesRequest;
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingInstancesResult;
import com.amazonaws.services.autoscaling.model.SetDesiredCapacityRequest;
import com.amazonaws.services.autoscaling.model.SetDesiredCapacityResult;
import com.amazonaws.services.autoscaling.model.TerminateInstanceInAutoScalingGroupRequest;
import com.amazonaws.services.autoscaling.model.TerminateInstanceInAutoScalingGroupResult;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClientBuilder;
import com.amazonaws.services.elasticloadbalancing.model.DeregisterInstancesFromLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeInstanceHealthRequest;
import com.amazonaws.services.elasticloadbalancing.model.DescribeInstanceHealthResult;
import com.amazonaws.services.elasticloadbalancing.model.InstanceState;

/**
 * 
 *
 */
public class App 
{
	
	static class AWSAutoScalingGroup
	{
		static final int MAX_WAIT_TIME_IN_MINUTES = 3;
		
		String name;
		AmazonAutoScaling autoScaling;
		AmazonEC2 ec2Client;
		AmazonElasticLoadBalancing elbClient;
		
		AutoScalingGroup asg;
		String lbName;
		int min;
		int desired;
		int max;
		int previousRunningCount;
		List<String> instanceIds;
		List<String> oldInstanceIds;
		Map<String,Map<String,String>> instanceProperties;
		
		AWSAutoScalingGroup(String name)
		{
			this.name = name;
			autoScaling = AmazonAutoScalingClientBuilder.defaultClient();
			ec2Client = AmazonEC2ClientBuilder.defaultClient();
			elbClient = AmazonElasticLoadBalancingClientBuilder.defaultClient();
			
			getStatus();
			
			previousRunningCount = findRunningInstanceCount();
			oldInstanceIds = instanceIds;
			
			printStatus();

			validateASG();
		}
		
		private void getStatus() 
		{
			getASGProperties();
			getInstanceAttributes();
		}

		private void getASGProperties() 
		{
			DescribeAutoScalingGroupsRequest req = new DescribeAutoScalingGroupsRequest();
			req.setAutoScalingGroupNames(Arrays.asList(new String[] { name }));
			DescribeAutoScalingGroupsResult ret = autoScaling.describeAutoScalingGroups(req);
			
			if (ret.getAutoScalingGroups().isEmpty())
				throw new RuntimeException("Can not find ASG with name: " + name);
			asg = ret.getAutoScalingGroups().get(0);
			
			lbName = asg.getLoadBalancerNames().size() == 1 ? asg.getLoadBalancerNames().get(0) : null;
			max = asg.getMaxSize();
			min = asg.getMinSize();
			desired = asg.getDesiredCapacity();
		}

		private void validateASG() 
		{
			if (min != desired)
				throw new RuntimeException("Min capacity should be equal to desired capacity!. min=" + min + ", desired=" + desired);
			if (desired != previousRunningCount)
				throw new RuntimeException("Current running instance count should be equal to desired capacity!. running=" + previousRunningCount + ", desired=" + desired);
			if (max != 2 * desired)
				throw new RuntimeException("Max capacity should be equal to 2 * desired capacity!. max=" + max + ", desired=" + desired);
			if (lbName == null)
				throw new RuntimeException("ASG should have a load balancer");
		}

		private int findRunningInstanceCount() 
		{
			int runningInstanceCount = 0;
			
			for (String instanceId : instanceIds)
			{
				Map<String, String> instanceMap = instanceProperties.get(instanceId);
				
				String publicIp = instanceMap.get("IP");
				String state = instanceMap.get("STATE");
				String healthStatus = instanceMap.get("HEALTHSTATUS");
				String lifeCycleState = instanceMap.get("LIFECYCLESTATE");
				String elbState = instanceMap.get("ELB_STATE");
				
				if (publicIp == null || publicIp.length() == 0)
					continue;
				if (!state.equals("running"))
					continue;
				if (!healthStatus.equals("Healthy"))
					continue;
				if (!lifeCycleState.equals("InService"))
					continue;
				if (!elbState.equals("InService"))
					continue;
				runningInstanceCount++;
			}
			return runningInstanceCount;
		}
		
		private void getInstanceAttributes() 
		{
			List<com.amazonaws.services.elasticloadbalancing.model.Instance> elbInstanceIds = new ArrayList<com.amazonaws.services.elasticloadbalancing.model.Instance>();

			instanceIds = new ArrayList();
			instanceProperties = new HashMap<String, Map<String,String>>();
		
			for (com.amazonaws.services.autoscaling.model.Instance i : asg.getInstances())
			{
				HashMap<String, String> instanceMap = new HashMap<String, String>();
				
				instanceMap.put("HEALTHSTATUS", i.getHealthStatus());
				instanceMap.put("LIFECYCLESTATE", i.getLifecycleState());
				
				instanceProperties.put(i.getInstanceId(), instanceMap);
				
				instanceIds.add(i.getInstanceId());
			}
			
			DescribeInstancesRequest req = new DescribeInstancesRequest();
			req.setInstanceIds(instanceIds);
			DescribeInstancesResult res = ec2Client.describeInstances(req);
			
			for (Reservation r: res.getReservations())
			{
				for (Instance i: r.getInstances())
				{
					Map<String, String> instanceMap = instanceProperties.get(i.getInstanceId());
					
					instanceMap.put("IP", i.getPublicIpAddress());
					instanceMap.put("STATE", i.getState().getName());
					
					if (i.getState().getName().equals("running") && 
						instanceMap.get("HEALTHSTATUS").equals("Healthy") && 
						instanceMap.get("LIFECYCLESTATE").equals("InService") )
						elbInstanceIds.add(new com.amazonaws.services.elasticloadbalancing.model.Instance(i.getInstanceId()));
				}
			}
			
			DescribeInstanceHealthRequest arg = new DescribeInstanceHealthRequest();
			arg.setLoadBalancerName(lbName);
			arg.setInstances(elbInstanceIds);
			DescribeInstanceHealthResult ret = elbClient.describeInstanceHealth(arg);
			
			for (InstanceState is : ret.getInstanceStates())
			{
				Map<String, String> instanceMap = instanceProperties.get(is.getInstanceId());
				
				instanceMap.put("ELB_STATE", is.getState());
			}
		}

		void printStatus()
		{
			System.out.println("ASG " + name + " min=" + min + ", max=" + max + ", desired=" + desired);
			System.out.println("ASG " + name + " is running instance(s) : ");
			System.out.println("----");
			
			
			for (String instanceId : instanceIds)
			{
				Map<String, String> instanceMap = instanceProperties.get(instanceId);

				String publicIp = instanceMap.get("IP");
				String state = instanceMap.get("STATE");
				String healthStatus = instanceMap.get("HEALTHSTATUS");
				String lifeCycleState = instanceMap.get("LIFECYCLESTATE");
				String elbState = instanceMap.get("ELB_STATE");
				
				System.out.println(" " + instanceId + "\t\t" + state + "\t\t" + healthStatus + "\t\t" + lifeCycleState + "\t\t" + publicIp + "\t\t" + elbState);
			}
			System.out.println("----");
		}

		private boolean allInstancesInService()
		{
			for (String instanceId : instanceIds)
			{
				Map<String, String> instanceMap = instanceProperties.get(instanceId);

				String publicIp = instanceMap.get("IP");
				String state = instanceMap.get("STATE");
				String healthStatus = instanceMap.get("HEALTHSTATUS");
				String lifeCycleState = instanceMap.get("LIFECYCLESTATE");
				String elbState = instanceMap.get("ELB_STATE");
				
				if (publicIp == null || publicIp.length() == 0)
					return false;
				if (!state.equals("running"))
					return false;
				if (!healthStatus.equals("HEALTHY"))
					return false;
				if (!lifeCycleState.equals("InService"))
					return false;
				if (!elbState.equals("InService"))
					return false;
			}
			return true;
		}
		
		void launchNewInstances()
		{
			int newDesiredSize = desired * 2;

			System.out.println("Launching new instances within ASG " + name + ", setting desired capacity to " + newDesiredSize);
			
			if (setDesiredCapacityAndWait(newDesiredSize))
				System.out.println("OK");				
			else 
				throw new RuntimeException("Can not set ASG desired size to " + newDesiredSize);
		}
		
		void removeOldInstancesFromELB()
		{
			System.out.println("Unregistering old instances from ELB");
			List<com.amazonaws.services.elasticloadbalancing.model.Instance> elbInstanceIds = new ArrayList<com.amazonaws.services.elasticloadbalancing.model.Instance>();

			for (String instanceId : oldInstanceIds)
			{
				elbInstanceIds.add(new com.amazonaws.services.elasticloadbalancing.model.Instance(instanceId));
			}

			DeregisterInstancesFromLoadBalancerRequest req = new DeregisterInstancesFromLoadBalancerRequest();
			req.setLoadBalancerName(lbName);
			req.setInstances(elbInstanceIds);
			
			elbClient.deregisterInstancesFromLoadBalancer(req);
			
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			getStatus();
			printStatus();
			System.out.println("OK");
		}

		void terminateOldInstances()
		{
			System.out.println("Terminating old instances in ASG " + name + " by setting desired capacity to " + previousRunningCount);
			
			if (setDesiredCapacityAndWait(previousRunningCount))
				System.out.println("OK");				
			else 
				throw new RuntimeException("Can not set ASG desired size to " + previousRunningCount);
		}
		
		boolean setDesiredCapacityAndWait(int newSize)
		{
			SetDesiredCapacityRequest req = new SetDesiredCapacityRequest();
			req.setAutoScalingGroupName(name);
			req.setDesiredCapacity(newSize);
			SetDesiredCapacityResult res = autoScaling.setDesiredCapacity(req);

			boolean success = false;
			String newState = null;
			int totalWaitTimeInSecs = 0;
			
			while (totalWaitTimeInSecs < MAX_WAIT_TIME_IN_MINUTES * 60)
			{
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				totalWaitTimeInSecs += 10;
				
				getStatus();
				printStatus();
				
				success = newSize == findRunningInstanceCount();
				if (success)
					break;
			}
			if (!success)
			{
				getStatus();
				printStatus();
				
				success = newSize == findRunningInstanceCount();
			}
			return success;
		}
		
	}
	
    public static void main( String[] args )
    {
    	// assumptions:
    	//  1. min=desired size
    	//  2. max=2*min
    	//  3. termination policy is OldestInstance (to not to terminate new instances that runs new version of the app)
    	// 
    	// process:
    	//  1. set desired=max to launch new instances that get new version of application
    	//  2. wait until the new instances are in service in elb
    	//  3. remove the old instances from elb
    	//  4. set desired=min to terminate old instances that run the old version of the app
    	//
    	
    	if (args.length != 1)
    	{
    		System.out.println("Usage: awsupdateasg <autoscalinggroupname>");
    		return;
    	}
    	String autoScalingGroupName = args[0];
    	
    	AWSAutoScalingGroup asg = new AWSAutoScalingGroup(autoScalingGroupName);
    	
		asg.launchNewInstances();
		asg.removeOldInstancesFromELB();
		asg.terminateOldInstances();
    }

}
