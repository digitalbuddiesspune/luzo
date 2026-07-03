# AWS Deployment

This document describes the intended deployment shape for the current PotLudo
backend.

## Recommended Topology

- Backend: Amazon ECS Fargate
- Image registry: Amazon ECR
- HTTPS and WebSocket ingress: Application Load Balancer
- Certificate: AWS Certificate Manager
- DNS: Route 53
- Redis: Amazon ElastiCache for Redis
- RabbitMQ: the operator-provided AMQP endpoint for credit payouts
- MongoDB: MongoDB Atlas or another compatible managed MongoDB deployment
- Frontend: Vercel

## Important Scaling Note

Realtime match fan-out is now Redis-backed, so multiple ECS tasks can share
match updates through the configured Redis channel.

## Backend Environment Variables

Set these in ECS task definition secrets/environment configuration:

```dotenv
MONGODB_URI=
MONGODB_DATABASE=potludo
REDIS_HOST=
REDIS_PORT=6379
REDIS_USERNAME=
REDIS_PASSWORD=
REDIS_SSL_ENABLED=true
PORT=8080
APP_SESSION_TTL_DAYS=30
APP_GAMEPLAY_TURN_TIMEOUT_SECONDS=30
APP_GAMEPLAY_ROOM_MAX_PLAYERS=4
APP_GAMEPLAY_ONLINE_ENTRY_FEE=100
APP_GAMEPLAY_LOBBY_WAIT_MILLIS=60000
APP_GAMEPLAY_ROLL_DELAY_MILLIS=700
APP_GAMEPLAY_BOT_MOVE_DELAY_MILLIS=850
APP_GAMEPLAY_ADVANCE_DELAY_MILLIS=750
APP_WALLET_CURRENCY=INR
APP_WALLET_GUEST_STARTING_BALANCE=100000
APP_WALLET_PAYOUT_RAKE_BASIS_POINTS=0
APP_WALLET_HOUSE_USER_ID=house
APP_OPERATOR_BASE_URL=https://sp.adminsportal.com
APP_OPERATOR_LOGIN_PATH=/operator/user/login
APP_OPERATOR_USER_DETAIL_PATH=/service/user/detail
APP_OPERATOR_BALANCE_PATH=/service/operator/user/balance/v2
AMQP_URI=
APP_OPERATOR_CREDIT_EXCHANGE=/games/admin
APP_OPERATOR_CREDIT_QUEUE_NAME=games_cashout
APP_OPERATOR_CREDIT_ROUTING_KEY=games_cashout
APP_OPERATOR_GAME_ID=2
APP_INSTANCE_ID=
APP_INSTANCE_LOCK_KEY_PREFIX=potludo:lock
APP_INSTANCE_HEARTBEAT_KEY_PREFIX=potludo:instance
APP_INSTANCE_LOCK_TTL_MILLIS=5000
APP_INSTANCE_HEARTBEAT_TTL_MILLIS=15000
APP_REALTIME_REDIS_CHANNEL=potludo:realtime
APP_REALTIME_WEB_RTC_ICE_SERVERS=stun:stun.l.google.com:19302
APP_REALTIME_WEB_RTC_TURN_USERNAME=
APP_REALTIME_WEB_RTC_TURN_CREDENTIAL=
APP_WEB_ALLOWED_ORIGIN_PATTERNS=https://your-frontend.vercel.app,https://*.vercel.app,https://play.example.com
```

## SSL Certificate

The SSL certificate is not generated inside this repository. For AWS you should:

1. Request a public certificate in AWS Certificate Manager for the API hostname.
   Example: `api.example.com`
2. Validate the certificate using Route 53 DNS validation.
3. Attach that ACM certificate to the HTTPS listener on the Application Load
   Balancer.
4. Create a Route 53 alias record from `api.example.com` to the ALB.

## ECS Steps

1. Create an ECR repository.
2. Build and push the backend image using `server/Dockerfile`.
3. Create an ECS cluster.
4. Create an ECS task definition exposing container port `8080`.
5. Inject secrets from AWS Secrets Manager or SSM Parameter Store.
6. Create an ECS service behind an ALB target group.
7. Use ALB health check path `/actuator/health/readiness`.
8. Enable CloudWatch Logs and optionally Container Insights.

## Frontend Steps on Vercel

Set:

```dotenv
NEXT_PUBLIC_API_BASE_URL=https://api.example.com
NEXT_PUBLIC_OPERATOR_PLATFORM_ENABLED=false
```

That is enough for both HTTP requests and WebSocket URL generation because the
client derives `wss://` from the same base URL.

Set `NEXT_PUBLIC_OPERATOR_PLATFORM_ENABLED=true` only for deployments that are
launched by the external operator platform with the required query parameters.

## Health and Proxying

The backend is now configured with:

- forwarded header support for reverse proxies
- health probes through Spring Boot actuator
- configurable CORS origin patterns for production domains

## Local Development

These changes do not change the local flow:

- local frontend origins remain allowed by default
- Redis still works with local `docker compose up -d`
- if Redis is temporarily unavailable, local realtime falls back to in-process
  delivery on the current server instance
