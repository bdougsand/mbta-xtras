"""
Script that dumps the contents of a
"""
from datetime import datetime
import json
import os
import re
import subprocess
import sys
import tempfile

from azure.storage.blob import BlockBlobService, ContentSettings, PublicAccess


def parse_uri(mongo_uri):
    m = re.match(r"mongodb://([^/]+)/(\w+)", mongo_uri)
    return (m.group(1), m.group(2))


def mongo_dump(since_stamp=None, before_stamp=None):
    """

    """
    if since_stamp:
        since_dt = datetime.fromtimestamp(since_stamp)
    else:
        # NOTE: Should really be using local midnight on the first of the
        # month--but agency local, not server local.
        dt = datetime.utcnow()
        since_dt = dt.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        # Calculate the stamp:
        since_stamp = int(since_dt.timestamp())

    mongo_uri = os.environ.get("MONGO_URI")
    if mongo_uri:
        hostname, db = parse_uri(mongo_uri)
    else:
        hostname = "localhost"
        db = "mbta"
    collection = os.environ.get("MONGO_COLLECTION", "trip-stops")
    query = {"arrival-time": {"$gte": since_stamp}}
    outfile = "{collection}_{dt}.gz".format(collection=collection,
                                            dt=since_dt.strftime("%Y_%m"))
    subprocess.check_output(["mongodump",
                             "--host", hostname,
                             "--db", db,
                             "--collection", "trip-stops",
                             "--query", json.dumps(query),
                             "--gzip",
                             "--archive=" + outfile],
                            timeout=6000)

    return outfile


def get_credentials():
    name = os.environ.get("AZURE_ACCOUNT")
    key = os.environ.get("AZURE_KEY")
    creds = {}
    if not name or not key:
        creds_file = os.environ("AZURE_CREDENTIALS",
                                os.path.join(os.path.dirname(__file__),
                                            "credentials.json"))
        try:
            with open(creds_file) as f:
                creds = json.load(f)

        except:
            pass

    return (name or creds.get("account"), key or creds.get("account"))

def upload_blob(outfile):
    account_name, account_key = get_credentials()
    container_name = os.environ.get("AZURE_BUCKET", "mbtafyi")

    service = BlockBlobService(account_name=account_name,
                               account_key=account_key)
    # Ensure that the container exists:
    service.create_container(container_name,
                             public_access=PublicAccess.Container)

    service.create_blob_from_path(container_name,
                                  os.path.basename(outfile),
                                  outfile)
    os.unlink(outfile)


def do_main(args):
    try:
        outfile = mongo_dump()
    except subprocess.TimeoutExpired as _exc:
        sys.stderr.write("mongodump took more than 10 minutes to run\n")
        sys.stderr.flush()
        sys.exit(1)

    # Just show the exception if there's an error in the subprocess.
    # except subprocess.CalledProcessError as cpe:

    upload_blob(outfile)



if __name__ == "__main__":
    do_main(sys.argv)
