# JenkinsProject

A CI/CD pipeline defined in a `Jenkinsfile` and run by Jenkins on an AWS EC2 Ubuntu server, using Docker.

## Architecture

```
Developer ──push──► GitHub ──► Jenkins Controller (EC2) ──► Docker ──► Build / Test / Deploy
```

- **Source:** https://github.com/AgilaHarikumar/JenkinsProject
- **Jenkins job name:** `SingleStage`
- **Host:** AWS EC2 (Ubuntu), Jenkins on port `8080`
- **Build runner:** Docker, used by the `jenkins` user

The Jenkins controller reads the `Jenkinsfile` and sends each step to the node that executes it. Here the Built-In Node does the work.

---

## Prerequisites

- An AWS EC2 Ubuntu instance with SSH access (security group allows ports `22` and `8080`)
- Jenkins installed and running as a service
- Docker installed (`docker.io`)
- A GitHub repository containing a file named exactly `Jenkinsfile` at the repo root

Recommended instance size: **`t3.small` (2 GB RAM) or larger**. A 1 GB instance runs out of memory when Jenkins and Docker run together.

---

## Server Setup Commands

Run these on the EC2 server, as the `ubuntu` user unless stated otherwise.

### 1. Connect to the server

```powershell
# From Windows PowerShell
ssh -i your-key.pem ubuntu@<ec2-public-ip>
```

> `sudo apt ...` is a Linux command. It does not work in Windows PowerShell, so run it on the EC2 server.

### 2. Install Docker

```bash
sudo apt update
sudo apt install docker.io
```

### 3. Give Jenkins and ubuntu access to Docker

```bash
sudo usermod -aG docker jenkins
sudo usermod -aG docker ubuntu
sudo systemctl restart docker
```

- `usermod -aG docker <user>` adds the user to the `docker` group and keeps their existing groups.
- Never use `-G` without `-a`, because it replaces all existing groups.

### 4. Verify the group change was saved

```bash
getent group docker
# Expected: docker:x:<gid>:ubuntu,jenkins
```

### 5. Test Docker as the jenkins user

```bash
sudo su - jenkins      # fresh login so the new group is picked up
id                     # should list the docker group
docker run hello-world
exit                   # back to ubuntu
```

### 6. Restart Jenkins so the running process gets the new permission

```bash
sudo systemctl restart jenkins
```

### 7. Add swap space (needed on small instances)

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # persist after reboot
free -h                                                       # confirm Swap shows 2.0Gi
```

### 8. Free up disk space

```bash
df -h                    # disk usage
docker system df         # Docker disk usage
docker system prune -a   # remove unused images and containers (asks to confirm)
sudo apt clean
```

---

## Jenkins Job Configuration

1. Open Jenkins at `http://<ec2-public-ip>:8080` and log in.
2. Create the job: **New Item → `SingleStage` → Pipeline → OK**.
3. In the **Pipeline** section, set:

| Setting | Value |
|---|---|
| Definition | Pipeline script from SCM |
| SCM | Git |
| Repository URL | `https://github.com/AgilaHarikumar/JenkinsProject` |
| Credentials | Add a GitHub token only if the repo is private |
| Branch Specifier | `*/main` (use `*/master` if that is your branch) |
| Script Path | `Jenkinsfile` |

4. Save, then click **Build Now**.
5. Click the build number, then **Console Output**, to follow progress.

> **Script Path** must be exactly `Jenkinsfile`: no leading `/`, no spaces, and the same capitalization as the file in the repo.

---

## Pipeline Stages

> Replace this section with the contents of your own `Jenkinsfile`. The example below shows the typical shape.

```groovy
pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Build') {
            steps {
                sh 'echo "Building..."'
            }
        }
        stage('Test') {
            steps {
                sh 'echo "Testing..."'
            }
        }
        stage('Deploy') {
            steps {
                sh 'echo "Deploying..."'
            }
        }
    }

    post {
        success { echo 'Pipeline succeeded' }
        failure { echo 'Pipeline failed' }
    }
}
```

| Stage | Purpose |
|---|---|
| Checkout | Pull the latest code from GitHub |
| Build | Compile or package the application |
| Test | Run automated tests |
| Deploy | Release the application |

---

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `Sudo is disabled on this machine` (PowerShell) | `sudo apt` is a Linux command and does not work on Windows | Run it on the EC2 server over SSH |
| `permission denied ... /var/run/docker.sock` | User is not in the `docker` group, or the session started before it was added | `getent group docker`, then `sudo su - jenkins`, then `id`. Restart Jenkins with `sudo systemctl restart jenkins` |
| `sudo: I'm sorry jenkins. I'm afraid I can't do that` | The `jenkins` user has no sudo rights (by design) | `exit` to the `ubuntu` user and run the command there |
| `Unable to find ... /Jenkinsfile from git ...` | Wrong Script Path, branch, or filename | Set Script Path to `Jenkinsfile`, check the branch, check the file exists and is pushed |
| Build runs for several minutes with no progress | Low memory (about 900 MB RAM, no swap) | Add swap, or resize to `t3.small` or larger |
| Node goes offline (low disk or temp space) | Free disk space below the 1 GiB threshold | `docker system prune -a`, `sudo apt clean`, or enlarge the EBS volume |

Useful diagnostics:

```bash
id                                  # current user and groups
getent group docker                 # docker group members
ls -l /var/run/docker.sock          # socket should belong to group docker
free -h                             # memory and swap
df -h                               # disk usage
ps aux --sort=-%mem | head -5       # top memory consumers
sudo systemctl status jenkins       # Jenkins service state
sudo journalctl -u jenkins -n 50    # recent Jenkins logs
```

---

## Managing the EC2 Instance

### Console

**EC2 → Instances → select instance → Instance state → Stop instance** (choose Stop, not Terminate).

### AWS CLI

```powershell
# Find your instance ID
aws ec2 describe-instances --query "Reservations[].Instances[].[InstanceId,State.Name,InstanceType]" --output table

# Stop
aws ec2 stop-instances --instance-ids i-0123456789abcdef0

# Change instance type (only while stopped)
aws ec2 modify-instance-attribute --instance-id i-0123456789abcdef0 --instance-type "{\"Value\": \"t3.small\"}"

# Start
aws ec2 start-instances --instance-ids i-0123456789abcdef0
```

Notes:

- The public IP changes after stop/start unless you attach an Elastic IP.
- Data on the EBS volume is kept while the instance is stopped.
- `t3.small` is not free-tier eligible.

---

## Command Reference

| Command | Meaning |
|---|---|
| `id` | Show current user, user ID and groups |
| `getent group docker` | Look up the `docker` group and its members |
| `getent passwd jenkins` | Look up the `jenkins` user account |
| `usermod -aG docker jenkins` | Add `jenkins` to the `docker` group, keeping existing groups |
| `su - jenkins` | Switch to `jenkins` with a fresh login shell |
| `sudo su -` | Use admin rights to become root with a fresh login |
| `exit` | Leave the current shell or user session |
| `systemctl restart <service>` | Restart a service (`docker`, `jenkins`) |
| `docker run hello-world` | Test that Docker works |
| `docker system prune -a` | Remove unused Docker images and containers |
| `free -h` | Show memory and swap in human-readable units |
| `df -h` | Show disk usage |
| `fallocate`, `mkswap`, `swapon` | Create and enable a swap file |

---

## Security Notes

- Users in the `docker` group effectively have root access to the host. Add only trusted users.
- Keep the `jenkins` user without sudo rights.
- Restrict port `8080` in the EC2 security group to your own IP where possible.
- Store secrets (tokens, keys) in the Jenkins **Credentials** store, never in the Jenkinsfile or repo.
