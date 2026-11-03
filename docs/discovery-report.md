# Discovery Report: Handling Sales Day Demand for the Onward Logistics Order System

| **Lab** | Handling Sales Day Demand for the Onward Logistics Order System |
| --- | --- |
| **Name** | Effie Njoki |
| **Date** | 3 November 2026 |
| **Client** | Onward Logistics (see the Client Profile) |

## 1. What I found

### The client's need

Onward needs its order system to stay fast and available when order volume triples on sales days, adjusting by itself without anyone stepping in, and shrinking back afterwards so the company does not pay for peak capacity on quiet days.

### Solutions that exist

| **Solution** | **How it works** | **Trade off for this client** |
| --- | --- | --- |
| Fixed fleet sized for the peak | Run enough servers for the busiest sales day, all month. | Strength: simple, and capacity is always ready. Weakness: Onward pays for peak capacity every hour of every quiet day, which its thin margins cannot carry, and a bigger than expected peak still breaks it. |
| Manual scaling | Engineers watch the load and add or remove servers by hand. | Strength: nothing extra to build. Weakness: slow to react, needs someone awake during every sales event, and easy to get wrong under pressure. The CTO has ruled this out. |
| Automatic scaling behind a load balancer | One public load balancer spreads traffic across servers in two Availability Zones. An Auto Scaling group adds servers when CPU rises and removes them when it falls, and replaces any server that fails. | Strength: capacity follows demand, and failures heal without people. Weakness: new servers take a few minutes to be ready, and the limits must be set carefully to balance speed against cost. |

### What happens with no solution

If the order system cannot absorb a sales day spike, pages slow down and orders fail at the moment demand and revenue are highest. Merchants lose sales, customers lose confidence, and both may move to a competitor. Onward has already seen this: last year's peak caused slow pages and errors that cost merchants and customer trust.

A well documented example is Amazon Prime Day 2018. The site and app failed shortly after the sale opened, with problems lasting more than an hour. Reports at the time traced it to not having enough servers for the traffic, and estimates of lost sales ranged from about 72 to 99 million US dollars. For a smaller company like Onward, a similar failure would hurt more, because each lost merchant is a larger share of its business.

The opposite mistake is also costly: running peak sized capacity all month wastes money every quiet day.

### Sources

- AWS, Amazon EC2 Auto Scaling target tracking policies: https://docs.aws.amazon.com/autoscaling/ec2/userguide/as-scaling-target-tracking.html
- AWS, Regional NAT gateways: https://docs.aws.amazon.com/vpc/latest/userguide/nat-gateways-regional.html
- AWS, Amazon VPC pricing (NAT gateway and public IPv4): https://aws.amazon.com/vpc/pricing/
- AWS, Free Tier eligible EC2 instance types: https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html
- MobileSyrup, Amazon Prime Day 2018 outage loss estimates: https://mobilesyrup.com/2018/07/19/amazon-prime-day-outage-lost-1-million-minute/
- Spryker, commerce failures including Prime Day 2018 and its cause: https://spryker.com/blog/top-10-commerce-failures

## 2. The business value of my solution

### My chosen approach

I chose automatic scaling behind a load balancer, because it is the only option that meets all four leaders at once. It grows on its own when CPU rises (CTO), it keeps servers private behind one public entry point (Head of Security), it replaces failed servers and survives the loss of a zone (Head of Operations), and it shrinks back after the rush so quiet days stay cheap (Finance Lead).

Key design choices:

- **Capacity limits.** Minimum 2 servers, one per Availability Zone, so the loss of a server or a whole zone leaves one still serving. Maximum 6, because sales days bring about three times normal volume and 2 servers handle a normal day. The maximum also caps the bill.
- **What triggers scaling.** A target tracking policy keeps average CPU near 50 percent. This leaves headroom for sudden spikes while new servers start.
- **Server size.** t3.micro on Amazon Linux 2023. It is Free Tier eligible and enough for a small Java application.
- **How the application gets onto each server.** A launch script in the template installs Java, fetches a pinned version of the application from the repository, and starts it as a service under a non root user. Every new server is identical and nothing is installed by hand.
- **Outbound internet.** One Regional NAT Gateway. I compared three options. One zonal NAT gateway is cheapest, but if its zone fails, servers in the other zone lose internet access. Two zonal NAT gateways, one per zone, remove that risk but add more resources and route tables to manage. A Regional NAT Gateway gives the same zone resilience as two zonal gateways from one resource that spans zones automatically. It is billed per zone it is active in, so the hourly cost matches two zonal gateways while the template stays simpler. The brief also requires it.
- **Triggering a load spike securely.** I will raise CPU using AWS Systems Manager, which runs a timed command on the servers. No admin ports are opened, and IAM controls who can run it.

**Cost view for the Finance Lead.** The main cost drivers are the servers (2 on a quiet day, up to 6 at peak), the load balancer, the Regional NAT Gateway in two zones, and the public IPv4 addresses on the load balancer and NAT gateway. My estimate for a quiet day and a peak day is here: [AWS Pricing Calculator estimate link]

### Services I will use

| **Service** | **The job it does** | **Why this service, used this way** |
| --- | --- | --- |
| Amazon VPC | A private network with public and private subnets in two Availability Zones. | Two zones give resilience. Private subnets keep servers unreachable from the internet. |
| Application Load Balancer | The single public entry point. Spreads requests across servers and health checks each one. | Customers see one address however many servers run, and failed servers are removed from traffic automatically. |
| Amazon EC2 Auto Scaling | Keeps between 2 and 6 servers running, adding or removing them based on CPU. | Handles sales day demand without people and shrinks afterwards to control cost. |
| Amazon EC2 (t3.micro, Amazon Linux 2023) | Runs the Java order application. | Free Tier eligible and sized for a small application. Scale comes from more servers, not bigger ones. |
| Regional NAT Gateway | Lets private servers download updates and Java without being reachable from the internet. | Zone resilient outbound access from one resource, as the brief requires. |
| S3 gateway endpoint | Sends traffic to Amazon S3 privately, without passing through the NAT gateway. | Free, and reduces NAT data charges. |
| AWS Systems Manager | Session Manager for access and Run Command for the load test. | No SSH and no open admin ports, as the Head of Security requires. |
| IAM role with AWS managed policy | Gives servers only the permissions Systems Manager needs. | Least privilege, no long lived keys, and no inline policies. |
| Amazon CloudWatch | Tracks server CPU and drives the scaling policy. | Lets operations watch capacity change. |
| AWS CloudFormation with Git sync | Builds everything from one template in the repository. | Repeatable, reviewable, and rebuildable at any time, matching company standards. |

### Who benefits, and how I will know

| **Stakeholder** | **Benefit** | **How I would know** |
| --- | --- | --- |
| CTO | Sales day demand is handled automatically, and spending drops after the rush. | During a load test, the server count rises from 2 without anyone acting, then returns to 2 after the load stops. |
| Head of Security | Only the load balancer is public, and no admin ports are open. | Servers have no public IP address, and their security group accepts traffic only from the load balancer. |
| Head of Operations | Failed servers and a lost zone do not affect customers, and the answering server is visible. | After a server is terminated, a replacement appears and the page keeps loading. Refreshing the page shows different server IDs. |
| Finance Lead | Clear quiet day and peak day costs, with reasons. | A shared Pricing Calculator estimate, and capacity that never exceeds the maximum of 6. |

### Limits and risks of my design

- **New servers are not instant.** They take a few minutes to start and pass health checks, so a very sudden spike may cause brief slowness. For known sales dates, scheduled scaling ahead of the event would help, and is not included in this lab.
- **Orders are not stored in a shared database.** Each server keeps its own orders in memory, so orders are lost if a server is replaced. A production system would need a shared database.
- **No HTTPS.** The site uses HTTP because the lab has no domain or certificate. Production would need HTTPS to protect customer data in transit.
- **Burstable servers can cost extra.** t3 instances default to unlimited CPU credits, so long periods of high CPU add charges. Load tests will be short.
- **Dependency at launch.** Servers fetch Java and the application from the internet when they start. If those sources are unavailable, new servers fail their health checks. Baking the software into a custom AMI would remove this risk.
- **One Region only.** The design survives the loss of a zone, not a whole Region.