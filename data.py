import argparse
import subprocess
import boto3

def parsing_parametrs():
    parser = argparse.ArgumentParser(description='Cleenup tool')

    parser.add_argument('-i', '--s3_id',
                        type=str,
                        help="AWS ID for S3 user")
    parser.add_argument('-k', '--s3_key',
                        type=str,
                        help="AWS KEY for S3 user")
    parser.add_argument('-r', '--aws_region',
                        type=str,
                        help="AWS region for Terraform")
    parser.add_argument('-b', '--bucket',
                        type=str,
                        help="AWS bucket")
    parser.add_argument('-f', '--folder',
                        type=str,
                        help='Parh to folder for zipping')
    parser.add_argument('-m', '--mode',
                        required=True,
                        type=str,
                        choices=["LOCAL_TO_S3", "S3_TO_LOCAL"],
                        help="Choice environment for deployment")

    return parser.parse_args()


def upload_file_to_s3(bucket_name, filename):
    try:
        s3.Bucket(bucket_name).upload_file(Filename=filename, Key=filename)
        print("Uploaded successfully.")
        subprocess.run(f'rm -rf {filename}', shell=True)
    except BaseException:
        print(f"Can not upload file {filename} to {bucket_name} S3 bucket.")


def download_file_from_s3(bucket_name, filename):
    try:
        s3.Bucket(bucket_name).download_file(Filename=filename, Key=filename)
        print("Downloaded successfully.")
    except BaseException:
        print(
            f"Can not download file {filename} from {bucket_name} S3 bucket.")


def zip_folder(folder, archive_name="data.zip"):
    zip_command = f"sudo zip -rq {archive_name} {folder}"
    try:
        subprocess.run(zip_command, shell=True)
        print("Zipped successfully.")
    except BaseException:
        print(f"Can not zip folder {folder}")
    return archive_name


def unzip_archive(folder, archive_name="data.zip"):
    unzip_command = f"sudo unzip -q {archive_name} -d {folder}"
    try:
        subprocess.run(unzip_command, shell=True)
        print("Unzipped successfully.")
    except BaseException:
        print(f"Can not unzip file {archive_name} archive")
        print("Jenkins will be created with local ./data volume...")
    subprocess.run(f'rm -rf {archive_name}', shell=True)


if __name__ == "__main__":

    mode = parsing_parametrs().mode

    params = parsing_parametrs()

    s3 = boto3.resource(
        service_name="s3",
        region_name=params.aws_region,
        aws_access_key_id=params.s3_id,
        aws_secret_access_key=params.s3_key,
    )

    if mode == "LOCAL_TO_S3":
        archive_path = zip_folder(params.folder)
        upload_file_to_s3(params.bucket, archive_path)
    elif mode == "S3_TO_LOCAL":
        download_file_from_s3(params.bucket, params.folder + ".zip")
        unzip_archive("./")
    else:
        print("Uncorrect mode parameter.")
