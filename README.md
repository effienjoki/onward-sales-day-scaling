# onward-sales-day-scaling
# Onward Logistics: handling sales day demand

The Onward Logistics order system on Amazon EC2, built to absorb sales day traffic automatically and shrink back afterwards, with servers that are never reachable from the internet.

**Discovery report:** [docs/discovery-report.md](docs/discovery-report.md)

**Architecture diagram:** [diagrams/](diagrams/)

## What is in this repo

| Path | Purpose |
| --- | --- |
| `app/OrderApp.java` | Single full stack Java app: serves the order page and processes orders. Shows the owner, lab name, server ID and Availability Zone. Uses only the Java standard library |
| `infrastructure/network.yaml` | Network stack: VPC across two Availability Zones, public and private subnets, one Regional NAT Gateway, S3 gateway endpoint |
| `infrastructure/app.yaml` | App stack: ALB, launch template, Auto Scaling group, CPU scaling policy |
| `infrastructure/*-deployment.yaml` | Git sync deployment files: which template, parameters and tags |
| `docs/` | Discovery report |
| `diagrams/` | Architecture diagram (draw.io) |

## The two stacks

| Stack | Template | Creates |
| --- | --- | --- |
| `onward-dev-network` | `infrastructure/network.yaml` | VPC, 2 public subnets (load balancer only), 2 private subnets (servers), internet gateway, Regional NAT Gateway, S3 gateway endpoint |
| `onward-dev-orders-app` | `infrastructure/app.yaml` | Security groups, server IAM role, internet facing ALB, target group, launch template, Auto Scaling group, target tracking scaling policy |

The network stack exports the VPC and subnet IDs. The app stack imports them. Deploy the network first, delete it last.

## How it handles a sales day

- **One public entry point:** customers reach an Application Load Balancer. Servers sit in private subnets with no public IP.
- **Automatic scaling:** an Auto Scaling group runs 2 to 6 t3.micro servers and keeps average CPU near 50 percent. Servers are added when CPU rises and removed when it falls.
- **Self healing:** the ALB checks `/health` every 15 seconds. A server that fails is removed from traffic and replaced by Auto Scaling.
- **Zone resilience:** minimum 2 servers, one per Availability Zone. The Regional NAT Gateway serves both zones.

## How the app gets onto each server

Each new server runs a launch script (user data) that:

1. Installs Java 21 through the NAT gateway
2. Creates a non root user `onward`
3. Downloads `app/OrderApp.java` from this repository at a pinned commit (`AppSourceUrl` parameter)
4. Runs it as a systemd service that starts on boot and restarts if it crashes

To release a new app version, commit it, then update the commit ID in `AppSourceUrl` in `infrastructure/app-deployment.yaml`.

## Security

- Only the load balancer is public. Servers accept port 8080 from the load balancer's security group only.
- No SSH. Admin access uses AWS Systems Manager Session Manager, which needs no open inbound ports.
- Instance metadata requires IMDSv2.
- No inline IAM policies. The server role uses `AmazonSSMManagedInstanceCore` only.
- No credentials in this repository.

## Demonstrating scaling

Raise CPU on the servers with Systems Manager Run Command (no ports opened):

1. Systems Manager, **Run Command**, **AWS-RunShellScript**
2. Target instances by tag `Name = onward-dev-orders-server`
3. Command: `timeout 600 sh -c 'yes > /dev/null & yes > /dev/null & wait'`

Watch the Auto Scaling group's activity history: servers are added within a few minutes, and removed again after the load stops.

## Run the app locally

    java app/OrderApp.java

Then open http://localhost:8080. Health check: http://localhost:8080/health

## Rebuild from scratch

1. `onward-dev-network`: Git sync, deployment file `infrastructure/network-deployment.yaml`
2. `onward-dev-orders-app`: Git sync, deployment file `infrastructure/app-deployment.yaml`

**Delete in reverse order:** app stack first, then network.

## Prerequisites created once (bootstrap exceptions)

- GitHub connection used by Git sync
- CloudFormation deploy role `onward-dev-cloudformation-deploy-role`
- One Git sync role per stack, created when the stack is linked

## Standards

Names follow `onward-<env>-<purpose>`. Every stack is tagged `Project=onward`, `Owner=Effie Njoki`, `Environment=dev`. Region: `eu-west-1`.
