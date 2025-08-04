
import numpy as np
import os
import sys

def split_npz_file(npz_file_path):
    """
    Splits an .npz file into individual .npy files.

    Args:
        npz_file_path (str): The path to the .npz file.
    """
    try:
        # Create a directory to store the split files
        directory = os.path.splitext(npz_file_path)[0]
        if not os.path.exists(directory):
            os.makedirs(directory)

        # Load the .npz file
        with np.load(npz_file_path) as data:
            # data.files is a list of the names of the arrays in the file
            for file_name in data.files:
                # Construct the full path for the new .npy file
                new_file_path = os.path.join(directory, f"{file_name}.npy")
                # Save the array to the new file
                np.save(new_file_path, data[file_name])
                print(f"Saved {new_file_path}")

    except FileNotFoundError:
        print(f"Error: The file '{npz_file_path}' was not found.")
    except Exception as e:
        print(f"An error occurred: {e}")

if __name__ == '__main__':
    # Provide the path to your .npz file
    npz_file = 'voices-v1.0.bin'
    split_npz_file(npz_file)
