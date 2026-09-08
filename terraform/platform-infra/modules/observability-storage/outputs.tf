output "loki_bucket" {
  value = aws_s3_bucket.loki.bucket
}

output "loki_role_arn" {
  value = aws_iam_role.loki.arn
}

output "tempo_bucket" {
  value = aws_s3_bucket.tempo.bucket
}

output "tempo_role_arn" {
  value = aws_iam_role.tempo.arn
}
